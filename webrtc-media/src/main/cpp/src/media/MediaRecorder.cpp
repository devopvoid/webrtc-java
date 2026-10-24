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

#include "media/MediaRecorder.h"
#include "media/ErrorText.h"

#include <chrono>
#include <cstring>
#include <utility>

extern "C" {
#include <libavutil/dict.h>
}

namespace ffmpeg
{
	namespace
	{
		// How much may wait for the writer, and how much may wait for the
		// file header, before frames are dropped: far more than a healthy
		// recording ever holds, far less than memory lasts.
		constexpr size_t kMaxQueuedBytes = 64 * 1024 * 1024;
		constexpr size_t kMaxPendingBytes = 64 * 1024 * 1024;

		// How long the tracks that are ready wait for the others before the
		// file starts without them.
		constexpr int64_t kTrackWaitUs = 3000000;

		// How often a track that waits for a key frame asks for one.
		constexpr int64_t kKeyFrameRequestIntervalUs = 1000000;

		// How long the recorded SSRC of a track has to be silent before a
		// different one takes its place.
		constexpr int64_t kSsrcSwitchUs = 1000000;

		// How often the writer looks at the clock when nothing arrives, for
		// the header timeout and for key frame requests it held back.
		constexpr std::chrono::milliseconds kWriterTick(250);

		bool IsMp4(const AVOutputFormat * format)
		{
			return std::strcmp(format->name, "mp4") == 0 || std::strcmp(format->name, "mov") == 0;
		}
	}

	MediaRecorder::MediaRecorder(const webrtc_java_api * api, std::string path) :
		api_(api),
		path_(std::move(path))
	{
	}

	MediaRecorder::~MediaRecorder()
	{
		Stop();

		if (context_ != nullptr) {
			if (context_->pb != nullptr && !(context_->oformat->flags & AVFMT_NOFILE)) {
				avio_closep(&context_->pb);
			}

			avformat_free_context(context_);
		}
	}

	void MediaRecorder::SetObserver(std::unique_ptr<MediaRecorderObserver> observer)
	{
		observer_ = std::move(observer);
	}

	int MediaRecorder::Open()
	{
		// The container follows the file name; a name FFmpeg cannot place
		// gets Matroska, which holds every codec WebRTC sends.
		int result = avformat_alloc_output_context2(&context_, nullptr, nullptr, path_.c_str());

		if (result < 0 || context_ == nullptr) {
			result = avformat_alloc_output_context2(&context_, nullptr, "matroska", path_.c_str());
		}
		if (result < 0) {
			return result;
		}

		// Frames arrive in real time, so the streams never drift far apart,
		// and holding back more for interleaving only costs memory.
		context_->max_interleave_delta = 2000000;

		if (!(context_->oformat->flags & AVFMT_NOFILE)) {
			result = avio_open(&context_->pb, path_.c_str(), AVIO_FLAG_WRITE);

			if (result < 0) {
				return result;
			}
		}

		return 0;
	}

	int MediaRecorder::AddTrack(void * frames)
	{
		std::lock_guard<std::mutex> lock(mutex_);

		if (started_ || stopped_) {
			api_->encoded_frames_release(frames);

			return -1;
		}

		auto input = std::make_unique<Input>();
		input->recorder = this;
		input->index = static_cast<int>(inputs_.size());
		input->frames = frames;

		tracks_.push_back(std::make_unique<RecorderTrack>(input->index));
		inputs_.push_back(std::move(input));

		return static_cast<int>(inputs_.size()) - 1;
	}

	void MediaRecorder::Start()
	{
		{
			std::lock_guard<std::mutex> lock(mutex_);

			if (started_ || stopped_) {
				return;
			}

			started_ = true;
			accepting_ = true;
			start_us_ = api_->now_us();
		}

		thread_ = std::thread(&MediaRecorder::Run, this);

		// The inputs do not change once started, so the callbacks may hold on
		// to them without the lock.
		for (const auto & input : inputs_) {
			input->observer = api_->encoded_observer_add(input->frames, &MediaRecorder::OnEncodedFrame,
					input.get());

			// The observer keeps what it observes alive on its own.
			api_->encoded_frames_release(input->frames);
			input->frames = nullptr;
		}
	}

	bool MediaRecorder::Stop()
	{
		{
			std::lock_guard<std::mutex> lock(mutex_);

			if (stopped_) {
				return header_written_;
			}

			stopped_ = true;
		}

		// No callback runs once its observer is removed, so after this nothing
		// comes in any more.
		for (const auto & input : inputs_) {
			if (input->observer != nullptr) {
				api_->encoded_observer_remove(input->observer);
				input->observer = nullptr;
			}
			if (input->frames != nullptr) {
				api_->encoded_frames_release(input->frames);
				input->frames = nullptr;
			}
		}

		{
			std::lock_guard<std::mutex> lock(mutex_);

			accepting_ = false;
			stopping_ = true;
		}

		wake_.notify_all();

		if (thread_.joinable()) {
			thread_.join();
		}

		// The writer closes the file when it finishes. A recorder that never
		// started has no writer, and its file must not stay open either: the
		// caller deletes an empty file, which Windows refuses while it is.
		if (context_ != nullptr && context_->pb != nullptr && !(context_->oformat->flags & AVFMT_NOFILE)) {
			avio_closep(&context_->pb);
		}

		return header_written_;
	}

	void MediaRecorder::OnEncodedFrame(void * opaque, const wj_encoded_frame * frame)
	{
		Input * input = static_cast<Input *>(opaque);

		input->recorder->Enqueue(input, frame);
	}

	void MediaRecorder::Enqueue(Input * input, const wj_encoded_frame * frame)
	{
		if (frame->data == nullptr || frame->size == 0 || frame->size > INT32_MAX) {
			return;
		}

		const bool video = frame->media_type == WEBRTC_JAVA_MEDIA_VIDEO;

		std::lock_guard<std::mutex> lock(mutex_);

		if (!accepting_) {
			return;
		}

		if (!input->locked) {
			// A video track starts with a key frame; a decoder could make
			// nothing of what comes before.
			if (video && !frame->key_frame) {
				WantKeyFrameLocked(input);
				return;
			}

			input->locked = true;
			input->ssrc = frame->ssrc;
			input->mime_type = frame->mime_type != nullptr ? frame->mime_type : "";
		}
		else if (frame->ssrc != input->ssrc) {
			// Another simulcast layer, which is not recorded; or the stream
			// was restarted under a new SSRC, which takes over once the old
			// one has gone quiet.
			if (frame->time_us - input->last_time_us < kSsrcSwitchUs) {
				return;
			}
			if (video && !frame->key_frame) {
				WantKeyFrameLocked(input);
				return;
			}

			input->ssrc = frame->ssrc;
		}

		if (frame->mime_type == nullptr || input->mime_type != frame->mime_type) {
			// A stream in a file cannot change its codec halfway.
			if (!input->reported_codec_change) {
				input->reported_codec_change = true;

				pending_warnings_.push_back("Track " + std::to_string(input->index)
						+ " changed its codec and is no longer recorded");
				poke_ = true;
				wake_.notify_one();
			}

			return;
		}

		if (video && input->waiting_for_key_frame) {
			if (!frame->key_frame) {
				WantKeyFrameLocked(input);
				return;
			}

			input->waiting_for_key_frame = false;
		}

		if (queued_bytes_ + frame->size > kMaxQueuedBytes) {
			// The writer does not keep up. Video goes on from the next key
			// frame, since everything up to it could not be decoded anyway.
			if (video) {
				input->waiting_for_key_frame = true;
				WantKeyFrameLocked(input);
			}

			return;
		}

		RecordedFrame recorded;
		recorded.packet = PacketPtr(av_packet_alloc());

		if (recorded.packet == nullptr
				|| av_new_packet(recorded.packet.get(), static_cast<int>(frame->size)) < 0) {
			return;
		}

		std::memcpy(recorded.packet->data, frame->data, frame->size);

		recorded.track = input->index;
		recorded.mime_type = input->mime_type;
		recorded.ssrc = frame->ssrc;
		recorded.rtp_timestamp = frame->rtp_timestamp;
		recorded.time_us = frame->time_us;
		recorded.key_frame = frame->key_frame != 0;
		recorded.width = frame->width;
		recorded.height = frame->height;

		input->last_time_us = frame->time_us;

		queued_bytes_ += frame->size;
		queue_.push_back(std::move(recorded));

		wake_.notify_one();
	}

	void MediaRecorder::WantKeyFrameLocked(Input * input)
	{
		if (!input->key_frame_wanted) {
			input->key_frame_wanted = true;
			poke_ = true;

			wake_.notify_one();
		}
	}

	void MediaRecorder::RestartAtKeyFrame(int track)
	{
		std::lock_guard<std::mutex> lock(mutex_);

		Input * input = inputs_[track].get();
		input->waiting_for_key_frame = true;

		WantKeyFrameLocked(input);
	}

	void MediaRecorder::CollectKeyFrameRequestsLocked(std::vector<int> * tracks, int64_t now_us)
	{
		for (const auto & input : inputs_) {
			if (input->key_frame_wanted
					&& now_us - input->last_key_frame_request_us >= kKeyFrameRequestIntervalUs) {
				input->key_frame_wanted = false;
				input->last_key_frame_request_us = now_us;

				tracks->push_back(input->index);
			}
		}
	}

	void MediaRecorder::Run()
	{
		std::deque<RecordedFrame> batch;
		std::vector<std::string> warnings;
		std::vector<int> key_frame_requests;

		std::unique_lock<std::mutex> lock(mutex_);

		while (true) {
			wake_.wait_for(lock, kWriterTick, [this] {
				return !queue_.empty() || stopping_ || poke_;
			});

			poke_ = false;

			batch.swap(queue_);
			queued_bytes_ = 0;
			warnings.swap(pending_warnings_);

			CollectKeyFrameRequestsLocked(&key_frame_requests, api_->now_us());

			const bool stopping = stopping_;

			lock.unlock();

			for (const std::string & warning : warnings) {
				Warn(warning);
			}
			for (int track : key_frame_requests) {
				if (observer_ != nullptr) {
					observer_->OnKeyFrameNeeded(track);
				}
			}

			warnings.clear();
			key_frame_requests.clear();

			while (!batch.empty()) {
				RecordedFrame frame = std::move(batch.front());
				batch.pop_front();

				Handle(std::move(frame));
			}

			// Nothing may arrive for a track that is waited for, so the
			// clock is looked at here as well.
			MaybeWriteHeader(false);

			lock.lock();

			if (stopping && queue_.empty()) {
				break;
			}
		}

		lock.unlock();

		Finish();
	}

	void MediaRecorder::Handle(RecordedFrame frame)
	{
		if (failed_) {
			return;
		}

		RecorderTrack & track = *tracks_[frame.track];

		if (track.GetStatus() == RecorderTrack::Status::kPending) {
			std::string message;
			RecorderTrack::Status status = track.Probe(frame, context_->oformat, &message);

			if (status == RecorderTrack::Status::kUnsupported) {
				Warn(message);
				return;
			}
			if (status == RecorderTrack::Status::kPending) {
				// A key frame that did not describe the stream, e.g. one
				// without parameter sets; the next one may.
				if (track.IsVideo()) {
					RestartAtKeyFrame(frame.track);
				}
				return;
			}
			if (first_ready_us_ == 0) {
				first_ready_us_ = api_->now_us();
			}
		}

		if (track.GetStatus() != RecorderTrack::Status::kReady) {
			return;
		}

		if (!header_written_) {
			pending_bytes_ += static_cast<size_t>(frame.packet->size);
			pending_.push_back(std::move(frame));

			MaybeWriteHeader(false);
			return;
		}

		// A track that got ready only after the header has no stream.
		if (track.GetStream() != nullptr) {
			Write(frame);
		}
	}

	void MediaRecorder::Write(RecordedFrame & frame)
	{
		AVPacket * packet = tracks_[frame.track]->Stamp(frame, start_us_);

		// Takes the packet's reference and leaves the packet blank.
		int result = av_interleaved_write_frame(context_, packet);

		if (result < 0) {
			Fail("Writing to " + path_ + " failed", result);
		}
	}

	bool MediaRecorder::MaybeWriteHeader(bool finishing)
	{
		if (header_written_) {
			return true;
		}
		if (failed_ || first_ready_us_ == 0) {
			return false;
		}

		bool all_known = true;

		for (const auto & track : tracks_) {
			if (track->GetStatus() == RecorderTrack::Status::kPending) {
				all_known = false;
			}
		}

		if (!all_known && !finishing
				&& api_->now_us() - first_ready_us_ < kTrackWaitUs
				&& pending_bytes_ < kMaxPendingBytes) {
			return false;
		}

		for (const auto & track : tracks_) {
			if (track->GetStatus() == RecorderTrack::Status::kReady) {
				int result = track->CreateStream(context_);

				if (result < 0) {
					Fail("Adding track " + std::to_string(track->GetIndex()) + " to the file failed", result);
					pending_.clear();
					return false;
				}
			}
			else if (track->GetStatus() == RecorderTrack::Status::kPending) {
				Warn("Track " + std::to_string(track->GetIndex())
						+ " had no media to record when the recording began, and is left out");
			}
		}

		AVDictionary * options = nullptr;

		if (IsMp4(context_->oformat)) {
			// Fragmented, so that a recording cut short, by a crash or a full
			// disk, still plays up to where it stopped; and in fragments of a
			// second rather than per key frame, which WebRTC sends rarely and
			// which would hold whole minutes in memory.
			av_dict_set(&options, "movflags", "+empty_moov+default_base_moof", 0);
			av_dict_set(&options, "frag_duration", "1000000", 0);
		}

		int result = avformat_write_header(context_, &options);

		av_dict_free(&options);

		if (result < 0) {
			Fail("Writing the header of " + path_ + " failed", result);
			pending_.clear();
			return false;
		}

		header_written_ = true;

		if (observer_ != nullptr) {
			observer_->OnStarted();
		}

		while (!pending_.empty() && !failed_) {
			RecordedFrame frame = std::move(pending_.front());
			pending_.pop_front();

			Write(frame);
		}

		pending_.clear();
		pending_bytes_ = 0;

		return true;
	}

	void MediaRecorder::Finish()
	{
		MaybeWriteHeader(true);

		if (header_written_) {
			// Even after a failed write: whatever made it into the file
			// is only playable with a trailer.
			int result = av_write_trailer(context_);

			if (result < 0 && !failed_) {
				Fail("Finishing " + path_ + " failed", result);
			}
		}

		pending_.clear();

		if (context_->pb != nullptr && !(context_->oformat->flags & AVFMT_NOFILE)) {
			avio_closep(&context_->pb);
		}
	}

	void MediaRecorder::Fail(const std::string & message, int error)
	{
		failed_ = true;

		{
			std::lock_guard<std::mutex> lock(mutex_);

			// Nothing more goes into the file, so nothing needs copying.
			accepting_ = false;
		}

		if (observer_ != nullptr) {
			observer_->OnError(message + ": " + ErrorText(error));
		}
	}

	void MediaRecorder::Warn(const std::string & message)
	{
		if (observer_ != nullptr) {
			observer_->OnWarning(message);
		}
	}
}
