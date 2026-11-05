/*
 * Copyright 2026 Alex Andres
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "media/MediaPacer.h"
#include "media/AudioDecoder.h"
#include "media/PlaybackProbe.h"
#include "media/VideoGeometry.h"

#include <algorithm>
#include <limits>
#include <utility>

namespace
{
	// Runs when WebRTC drops its last reference to the pixels of a pushed
	// frame, on whichever thread that happens to be.
	void ReleaseVideoFrame(void * opaque)
	{
		AVFrame * frame = static_cast<AVFrame *>(opaque);

		av_frame_free(&frame);
	}

	// What a frame keeps in memory, padding and alignment included, which is
	// what its buffers hold rather than what its size works out at.
	size_t FrameBytes(const AVFrame * frame)
	{
		size_t bytes = 0;

		for (const AVBufferRef * buffer : frame->buf) {
			if (buffer != nullptr) {
				bytes += buffer->size;
			}
		}

		for (int i = 0; i < frame->nb_extended_buf; ++i) {
			bytes += frame->extended_buf[i]->size;
		}

		return bytes;
	}
}

namespace ffmpeg
{
	MediaPacer::VideoItem::~VideoItem()
	{
		if (frame != nullptr) {
			av_frame_free(&frame);
		}
	}

	MediaPacer::VideoItem::VideoItem(VideoItem && other) noexcept
		: frame(other.frame), timestamp_us(other.timestamp_us),
		  loop_offset_us(other.loop_offset_us), bytes(other.bytes)
	{
		other.frame = nullptr;
	}

	MediaPacer::VideoItem & MediaPacer::VideoItem::operator=(VideoItem && other) noexcept
	{
		if (this != &other) {
			if (frame != nullptr) {
				av_frame_free(&frame);
			}

			frame = other.frame;
			timestamp_us = other.timestamp_us;
			loop_offset_us = other.loop_offset_us;
			bytes = other.bytes;
			other.frame = nullptr;
		}

		return *this;
	}

	MediaPacer::MediaPacer(const webrtc_java_api * api, void * video_source,
			void * audio_source)
		: api_(api),
		  video_source_(video_source),
		  audio_source_(audio_source)
	{
		// The pacing thread pushes into the sources long after the call that
		// handed them over, so they must not go away when the application
		// disposes of them first.
		if (video_source_ != nullptr) {
			api_->video_source_retain(video_source_);
		}
		if (audio_source_ != nullptr) {
			api_->audio_source_retain(audio_source_);
		}
	}

	MediaPacer::~MediaPacer()
	{
		Stop();

		// Nothing pushes any more. A frame WebRTC still holds does not need
		// the source, only its release callback, which frees the frame alone.
		if (video_source_ != nullptr) {
			api_->video_source_release(video_source_);
		}
		if (audio_source_ != nullptr) {
			api_->audio_source_release(audio_source_);
		}
	}

	void MediaPacer::Start()
	{
		std::lock_guard<std::mutex> lock(mutex_);

		if (running_) {
			return;
		}

		running_ = true;
		thread_ = std::thread(&MediaPacer::Run, this);
	}

	void MediaPacer::Stop()
	{
		{
			std::lock_guard<std::mutex> lock(mutex_);

			if (!running_ && !thread_.joinable()) {
				return;
			}

			running_ = false;

			// Everyone waiting has to see that nothing more is coming: the
			// pacing thread, and a decode thread held up by a full queue.
			work_.notify_all();
			video_space_.notify_all();
			audio_space_.notify_all();
		}

		if (thread_.joinable()) {
			thread_.join();
		}

		std::lock_guard<std::mutex> lock(mutex_);

		video_queue_.clear();
		audio_queue_.clear();
		video_bytes_ = 0;
	}

	bool MediaPacer::PushVideo(AVFrame * frame, int64_t timestamp_us, int64_t loop_offset_us,
			uint64_t generation)
	{
		const size_t bytes = FrameBytes(frame);

		std::unique_lock<std::mutex> lock(mutex_);

		const auto room = [this, generation, bytes] {
			return !running_ || generation != generation_ || HasVideoRoom(bytes);
		};

		if (!room()) {
			PlaybackProbe::WaitingPush waiting;

			video_space_.wait(lock, room);
		}

		if (!running_ || generation != generation_) {
			// The frame became ours on the way in, so it is ours to drop.
			av_frame_free(&frame);

			return false;
		}

		video_queue_.emplace_back(frame, timestamp_us + loop_offset_us, loop_offset_us, bytes);
		video_bytes_ += bytes;
		peak_video_bytes_ = std::max(peak_video_bytes_, video_bytes_);

		work_.notify_one();

		return true;
	}

	bool MediaPacer::PushAudio(std::vector<int16_t> && samples, int channels,
			int64_t timestamp_us, int64_t loop_offset_us, uint64_t generation)
	{
		std::unique_lock<std::mutex> lock(mutex_);

		const auto room = [this, generation] {
			if (!running_ || generation != generation_
					|| audio_queue_.size() < kAudioCapacity) {
				return true;
			}

			// Full, but blocking now would keep video from being decoded
			// while it is running short.
			return audio_queue_.size() < kAudioOverflowCapacity && IsVideoShort();
		};

		if (!room()) {
			PlaybackProbe::WaitingPush waiting;

			audio_space_.wait(lock, room);
		}

		if (!running_ || generation != generation_) {
			return false;
		}

		AudioItem item;

		item.samples = std::move(samples);
		item.channels = channels;
		item.timestamp_us = timestamp_us + loop_offset_us;
		item.loop_offset_us = loop_offset_us;

		audio_queue_.push_back(std::move(item));

		work_.notify_one();

		return true;
	}

	void MediaPacer::Pause()
	{
		std::lock_guard<std::mutex> lock(mutex_);

		if (paused_) {
			return;
		}

		paused_ = true;
		paused_at_us_ = api_->now_us();

		work_.notify_all();
	}

	void MediaPacer::Resume()
	{
		std::lock_guard<std::mutex> lock(mutex_);

		if (!paused_) {
			return;
		}

		// What is queued is due that much later than it was, so the mapping
		// moves with the pause instead of the queue draining all at once.
		base_us_ += api_->now_us() - paused_at_us_;
		paused_ = false;

		work_.notify_all();
	}

	void MediaPacer::Flush(int64_t position_us, bool exact)
	{
		std::lock_guard<std::mutex> lock(mutex_);

		FlushLocked(position_us, exact);
	}

	void MediaPacer::StartGeneration(int64_t position_us)
	{
		std::lock_guard<std::mutex> lock(mutex_);

		++generation_;

		// Should the seek fail, playback carries on from where it was.
		FlushLocked(position_us, false);
	}

	uint64_t MediaPacer::GetGeneration() const
	{
		std::lock_guard<std::mutex> lock(mutex_);

		return generation_;
	}

	void MediaPacer::FlushLocked(int64_t position_us, bool exact)
	{
		video_queue_.clear();
		audio_queue_.clear();
		video_bytes_ = 0;

		// Items only ever move the position forward, so a position left from
		// before a seek backwards would hold it there until they caught up.
		// Nor can what comes next be held to this one: a seek that is not
		// precise plays from a keyframe before it.
		position_us_ = position_us;
		position_offset_us_ = 0;
		position_set_ = false;

		// The next item delivered starts a new mapping, wherever it comes from.
		have_base_ = false;
		exact_start_ = exact;

		video_space_.notify_all();
		audio_space_.notify_all();
		work_.notify_all();
	}

	bool MediaPacer::IsDrained() const
	{
		std::lock_guard<std::mutex> lock(mutex_);

		// An item leaves its queue before it is handed over, so empty queues
		// alone would call the source played out while its last item is
		// still on its way into WebRTC.
		return video_queue_.empty() && audio_queue_.empty() && delivering_ == 0;
	}

	int64_t MediaPacer::GetPositionUs() const
	{
		std::lock_guard<std::mutex> lock(mutex_);

		return position_us_ - position_offset_us_;
	}

	void MediaPacer::AdvancePosition(int64_t timestamp_us, int64_t loop_offset_us)
	{
		if (position_set_ && timestamp_us <= position_us_) {
			return;
		}

		position_us_ = timestamp_us;
		position_offset_us_ = loop_offset_us;
		position_set_ = true;
	}

	size_t MediaPacer::GetQueuedVideoBytes() const
	{
		std::lock_guard<std::mutex> lock(mutex_);

		return video_bytes_;
	}

	size_t MediaPacer::GetPeakQueuedVideoBytes() const
	{
		std::lock_guard<std::mutex> lock(mutex_);

		return peak_video_bytes_;
	}

	bool MediaPacer::HasVideoRoom(size_t bytes) const
	{
		return video_queue_.empty() || (video_queue_.size() < kVideoCapacity
				&& video_bytes_ + bytes <= kVideoByteCapacity);
	}

	bool MediaPacer::IsVideoShort() const
	{
		return video_queue_.size() < kVideoCapacity / 2
				&& video_bytes_ < kVideoByteCapacity / 2;
	}

	bool MediaPacer::IsOwnThread() const
	{
		std::lock_guard<std::mutex> lock(mutex_);

		return thread_.get_id() == std::this_thread::get_id();
	}

	void MediaPacer::Run()
	{
		std::unique_lock<std::mutex> lock(mutex_);

		while (running_) {
			if (paused_) {
				work_.wait(lock);

				continue;
			}

			const int64_t now = api_->now_us();
			int64_t next_due_us = std::numeric_limits<int64_t>::max();
			bool delivered = false;

			// The mapping is made once, from whichever kind of media is ready
			// first, and holds for both from then on.
			if (!have_base_ && (!video_queue_.empty() || !audio_queue_.empty())) {
				int64_t first = !video_queue_.empty()
						? video_queue_.front().timestamp_us
						: audio_queue_.front().timestamp_us;

				if (!video_queue_.empty() && !audio_queue_.empty()) {
					first = std::min(first, audio_queue_.front().timestamp_us);
				}

				// Audio can get here ahead of video that comes before it, as
				// a decoder on many threads holds on to its first pictures.
				// Mapped from that audio, those pictures would be overdue,
				// and go out all at once.
				if (video_queue_.empty() && exact_start_ && first > position_us_
						&& first - position_us_ <= kMaxStartGapUs) {
					first = position_us_;
				}

				base_us_ = now - first;
				have_base_ = true;
			}

			if (!video_queue_.empty()) {
				const int64_t due = video_queue_.front().timestamp_us + base_us_;

				if (due <= now) {
					VideoItem item = std::move(video_queue_.front());

					video_queue_.pop_front();
					video_bytes_ -= item.bytes;
					AdvancePosition(item.timestamp_us, item.loop_offset_us);

					video_space_.notify_one();
					// A shorter video queue may be what lets audio past its
					// capacity.
					audio_space_.notify_one();

					++delivering_;
					lock.unlock();
					DeliverVideo(std::move(item), due);
					lock.lock();
					--delivering_;

					delivered = true;
				}
				else {
					next_due_us = std::min(next_due_us, due);
				}
			}

			if (!audio_queue_.empty()) {
				const int64_t due = audio_queue_.front().timestamp_us + base_us_;

				if (due <= now) {
					AudioItem item = std::move(audio_queue_.front());

					audio_queue_.pop_front();
					AdvancePosition(item.timestamp_us, item.loop_offset_us);

					audio_space_.notify_one();

					++delivering_;
					lock.unlock();
					DeliverAudio(item, due);
					lock.lock();
					--delivering_;

					delivered = true;
				}
				else {
					next_due_us = std::min(next_due_us, due);
				}
			}

			if (delivered) {
				// Something else may be due already.
				continue;
			}

			if (next_due_us == std::numeric_limits<int64_t>::max()) {
				// Nothing queued at all; a push or a stop wakes this.
				work_.wait(lock);
			}
			else {
				work_.wait_for(lock, std::chrono::microseconds(next_due_us - now));
			}
		}
	}

	void MediaPacer::DeliverVideo(VideoItem item, int64_t mapped_us)
	{
		AVFrame * frame = item.frame;

		if (video_source_ == nullptr || frame == nullptr) {
			// Nothing to deliver to; the item frees the frame as it goes out
			// of scope.
			return;
		}

		wj_i420_frame pushed = {};

		pushed.width = frame->width;
		pushed.height = frame->height;
		pushed.y = frame->data[0];
		pushed.u = frame->data[1];
		pushed.v = frame->data[2];
		pushed.stride_y = frame->linesize[0];
		pushed.stride_u = frame->linesize[1];
		pushed.stride_v = frame->linesize[2];
		pushed.rotation = GetFrameRotation(frame);
		pushed.timestamp_us = mapped_us;
		pushed.release = ReleaseVideoFrame;
		pushed.opaque = frame;

		// The frame belongs to the release callback now, which runs whether
		// the push succeeds or fails, so this item must not free it as well.
		item.frame = nullptr;

		const wj_color_space color = {
			static_cast<uint8_t>(frame->color_primaries),
			static_cast<uint8_t>(frame->color_trc),
			static_cast<uint8_t>(frame->colorspace),
			static_cast<uint8_t>(frame->color_range)
		};

		api_->video_source_push_color(video_source_, &pushed, &color);
	}

	void MediaPacer::DeliverAudio(const AudioItem & item, int64_t mapped_us)
	{
		if (audio_source_ == nullptr || item.samples.empty()) {
			return;
		}

		wj_audio_chunk chunk = {};

		chunk.samples = item.samples.data();
		chunk.sample_rate = AudioDecoder::kSampleRate;
		chunk.channels = item.channels;
		chunk.frames = AudioDecoder::kFramesPerChunk;
		chunk.timestamp_us = mapped_us;

		// The samples are copied during the call, so the item may go away as
		// soon as this returns.
		api_->audio_source_push(audio_source_, &chunk);
	}
}
