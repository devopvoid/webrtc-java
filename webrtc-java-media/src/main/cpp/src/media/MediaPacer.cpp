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
		: frame(other.frame), timestamp_us(other.timestamp_us)
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
	}

	MediaPacer::~MediaPacer()
	{
		Stop();
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
	}

	bool MediaPacer::PushVideo(AVFrame * frame, int64_t timestamp_us)
	{
		std::unique_lock<std::mutex> lock(mutex_);

		video_space_.wait(lock, [this] {
			return !running_ || video_queue_.size() < kVideoCapacity;
		});

		if (!running_) {
			// The frame became ours on the way in, so it is ours to drop.
			av_frame_free(&frame);

			return false;
		}

		video_queue_.emplace_back(frame, timestamp_us);

		work_.notify_one();

		return true;
	}

	bool MediaPacer::PushAudio(std::vector<int16_t> && samples, int channels,
			int64_t timestamp_us)
	{
		std::unique_lock<std::mutex> lock(mutex_);

		audio_space_.wait(lock, [this] {
			return !running_ || audio_queue_.size() < kAudioCapacity;
		});

		if (!running_) {
			return false;
		}

		AudioItem item;

		item.samples = std::move(samples);
		item.channels = channels;
		item.timestamp_us = timestamp_us;

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

	void MediaPacer::Flush()
	{
		std::lock_guard<std::mutex> lock(mutex_);

		video_queue_.clear();
		audio_queue_.clear();

		// The next item delivered starts a new mapping, wherever it comes from.
		have_base_ = false;

		video_space_.notify_all();
		audio_space_.notify_all();
		work_.notify_all();
	}

	bool MediaPacer::IsDrained() const
	{
		std::lock_guard<std::mutex> lock(mutex_);

		return video_queue_.empty() && audio_queue_.empty();
	}

	int64_t MediaPacer::GetPositionUs() const
	{
		std::lock_guard<std::mutex> lock(mutex_);

		return position_us_;
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

				base_us_ = now - first;
				have_base_ = true;
			}

			if (!video_queue_.empty()) {
				const int64_t due = video_queue_.front().timestamp_us + base_us_;

				if (due <= now) {
					VideoItem item = std::move(video_queue_.front());

					video_queue_.pop_front();
					position_us_ = item.timestamp_us;

					video_space_.notify_one();

					lock.unlock();
					DeliverVideo(std::move(item), due);
					lock.lock();

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
					position_us_ = std::max(position_us_, item.timestamp_us);

					audio_space_.notify_one();

					lock.unlock();
					DeliverAudio(item, due);
					lock.lock();

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
		pushed.rotation = 0;
		pushed.timestamp_us = mapped_us;
		pushed.release = ReleaseVideoFrame;
		pushed.opaque = frame;

		// The frame belongs to the release callback now, which runs whether
		// the push succeeds or fails, so this item must not free it as well.
		item.frame = nullptr;

		api_->video_source_push(video_source_, &pushed);
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
