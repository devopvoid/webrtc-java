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
#include "media/AllocationFault.h"
#include "media/ErrorText.h"
#include "media/WriteFault.h"

#include <algorithm>
#include <chrono>
#include <cstring>
#include <exception>
#include <limits>
#include <new>
#include <utility>

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

		// How long the segment before takes the frames of a video track that
		// has not reached a key frame in the next one, before the track waits
		// for it with nothing recorded.
		constexpr int64_t kDrainUs = 3000000;

		// How long a segment that is due to end waits for a key frame of a
		// video track before it ends at whatever frame comes.
		constexpr int64_t kRotationWaitUs = 3000000;

		// How many existing files a segment skips before it gives up.
		constexpr int kMaxSkippedSegments = 1000;

		// How often a replay buffer asks for key frames, which is how far
		// beyond its window it may have to reach back: a third of the window,
		// but neither often enough to cost a call much, nor so rarely that the
		// buffer holds much more than asked.
		constexpr int64_t kMinReplayKeyFrameIntervalUs = 2000000;
		constexpr int64_t kMaxReplayKeyFrameIntervalUs = 10000000;

		std::string SegmentPattern(const MediaRecorder::SegmentConfig & segments)
		{
			return segments.prefix + "%0" + std::to_string(segments.width) + "d" + segments.suffix;
		}
	}

	MediaRecorder::MediaRecorder(const webrtc_java_api * api, Mode mode, std::string path) :
		api_(api),
		mode_(mode),
		path_(std::move(path)),
		subject_(mode == Mode::kReplay ? "Buffering for replays" : "Recording to " + path_),
		out_of_memory_error_(subject_ + " failed: " + ErrorText(AVERROR(ENOMEM)))
	{
	}

	MediaRecorder::MediaRecorder(const webrtc_java_api * api, std::string path) :
		MediaRecorder(api, Mode::kFile, std::move(path))
	{
	}

	MediaRecorder::MediaRecorder(const webrtc_java_api * api, SegmentConfig segments) :
		MediaRecorder(api, Mode::kSegments, SegmentPattern(segments))
	{
		segments_ = std::move(segments);
		next_index_ = segments_.first_index;
	}

	MediaRecorder::MediaRecorder(const webrtc_java_api * api, ReplayConfig replay) :
		MediaRecorder(api, Mode::kReplay, std::string())
	{
		replay_ = replay;
		key_frame_interval_us_ = std::min(std::max(replay_.window_us / 3, kMinReplayKeyFrameIntervalUs),
				kMaxReplayKeyFrameIntervalUs);
	}

	MediaRecorder::~MediaRecorder()
	{
		Stop();
	}

	void MediaRecorder::SetObserver(std::unique_ptr<MediaRecorderObserver> observer)
	{
		observer_ = std::move(observer);
	}

	int MediaRecorder::Open()
	{
		if (mode_ == Mode::kFile) {
			output_ = std::make_unique<RecorderOutput>(path_, 0, 0);

			int result = output_->Open(false);

			if (result < 0) {
				return result;
			}

			format_ = output_->GetFormat();
		}
		else if (mode_ == Mode::kSegments) {
			// As a segment will choose it when it opens.
			const std::string name = "segment" + segments_.suffix;

			format_ = av_guess_format(nullptr, name.c_str(), nullptr);

			if (format_ == nullptr) {
				format_ = av_guess_format("matroska", nullptr, nullptr);
			}
			if (format_ == nullptr) {
				return AVERROR_MUXER_NOT_FOUND;
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

		const int index = static_cast<int>(inputs_.size());
		std::unique_ptr<Input> input;
		std::unique_ptr<RecorderTrack> track;

		try {
			AllocationFault::CheckAllocation();

			input = std::make_unique<Input>();
			track = std::make_unique<RecorderTrack>(index);

			// Room for both first, so that no track is added without its input.
			tracks_.reserve(tracks_.size() + 1);
			inputs_.reserve(inputs_.size() + 1);
		}
		catch (...) {
			// The frames were handed over, and nothing else releases them.
			api_->encoded_frames_release(frames);
			throw;
		}

		input->recorder = this;
		input->index = index;
		input->frames = frames;

		tracks_.push_back(std::move(track));
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
		}

		// Everything that can fail comes before the first frame is taken and
		// before there is a writer, which would finish the file on its way
		// out, so that taking the observers off again undoes all of it.
		try {
			const size_t count = tracks_.size();

			switched_.assign(count, true);
			awaiting_segment_.assign(count, false);
			key_times_.resize(count);
			last_key_us_.assign(count, 0);
			last_frame_us_.assign(count, std::numeric_limits<int64_t>::min());

			// The inputs do not change once started, so the callbacks may hold
			// on to them without the lock.
			for (const auto & input : inputs_) {
				input->observer = AllocationFault::CheckObserverAdd() ? nullptr
						: api_->encoded_observer_add(input->frames, &MediaRecorder::OnEncodedFrame, input.get());

				// Given a track and a callback, only memory can be missing.
				if (input->observer == nullptr) {
					throw std::bad_alloc();
				}
			}

			AllocationFault::CheckThreadStart();

			thread_ = std::thread(&MediaRecorder::Run, this);
		}
		catch (...) {
			// The recorder stays as it was before the call: it may be started
			// again, and stopping only has to release the frames and close
			// the file.
			RemoveObservers();

			std::lock_guard<std::mutex> lock(mutex_);

			started_ = false;
			throw;
		}

		{
			std::lock_guard<std::mutex> lock(mutex_);

			accepting_ = true;
			start_us_ = api_->now_us();
		}

		// The observers keep what they observe alive on their own.
		for (const auto & input : inputs_) {
			api_->encoded_frames_release(input->frames);
			input->frames = nullptr;
		}
	}

	MediaRecorder::Result MediaRecorder::Stop()
	{
		{
			std::lock_guard<std::mutex> lock(mutex_);

			if (stopped_) {
				return result_;
			}

			stopped_ = true;
		}

		// No callback runs once its observer is removed, so after this nothing
		// comes in any more.
		RemoveObservers();

		for (const auto & input : inputs_) {
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
		Close();

		// The writer has ended, so what it left is final. Media in the file
		// is kept even after a failure: what made it there may still play.
		Result result;

		if (header_written_) {
			result = failed_ ? Result::kPartial : Result::kComplete;
		}
		else {
			result = failed_ ? Result::kFailed : Result::kEmpty;
		}

		std::lock_guard<std::mutex> lock(mutex_);

		result_ = result;

		return result_;
	}

	MediaRecorder::Stats MediaRecorder::GetStats()
	{
		std::lock_guard<std::mutex> lock(mutex_);

		Stats stats;

		stats.max_queued_bytes = static_cast<int64_t>(max_queued_bytes_);
		stats.tracks.reserve(inputs_.size());

		for (const auto & input : inputs_) {
			stats.tracks.push_back(input->stats);
		}

		return stats;
	}

	bool MediaRecorder::SaveReplay(int id, std::string path)
	{
		std::lock_guard<std::mutex> lock(mutex_);

		if (mode_ != Mode::kReplay || !started_ || stopped_ || writer_ended_) {
			return false;
		}

		SaveRequest request;
		request.id = id;
		request.path = std::move(path);

		save_requests_.push_back(std::move(request));
		poke_ = true;
		wake_.notify_one();

		return true;
	}

	void MediaRecorder::RemoveObservers()
	{
		for (const auto & input : inputs_) {
			if (input->observer != nullptr) {
				api_->encoded_observer_remove(input->observer);
				input->observer = nullptr;
			}
		}
	}

	void MediaRecorder::OnEncodedFrame(void * opaque, const wj_encoded_frame * frame)
	{
		Input * input = static_cast<Input *>(opaque);

		// WebRTC calls this through the C interface, on threads of its own,
		// and nothing may be thrown back into it. A frame that cannot be
		// copied for want of memory is dropped, as is one the writer has no
		// room for.
		try {
			input->recorder->Enqueue(input, frame);
		}
		catch (...) {
			input->recorder->Dropped(input, frame->media_type == WEBRTC_JAVA_MEDIA_VIDEO);
		}
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

			// The name first, which may fail to be copied: a track is locked
			// only once all of it is set.
			input->mime_type = frame->mime_type != nullptr ? frame->mime_type : "";
			input->locked = true;
			input->ssrc = frame->ssrc;
		}
		else if (frame->ssrc != input->ssrc) {
			// Another simulcast layer, which is not recorded; or the stream
			// was restarted under a new SSRC, which takes over once the old
			// one has gone quiet.
			if (frame->time_us - input->last_time_us < kSsrcSwitchUs) {
				return;
			}
			if (video && !frame->key_frame) {
				input->stats.dropped_awaiting_key_frame++;
				WantKeyFrameLocked(input);
				return;
			}

			input->ssrc = frame->ssrc;
		}

		if (frame->mime_type == nullptr
				|| (mode_ == Mode::kFile && input->mime_type != frame->mime_type)) {
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

		if (input->mime_type != frame->mime_type) {
			// Segments and replays go on with the new codec, which the writer
			// describes anew, from a key frame.
			input->mime_type = frame->mime_type;

			if (video) {
				input->waiting_for_key_frame = true;
			}
		}

		if (video && input->waiting_for_key_frame) {
			if (!frame->key_frame) {
				input->stats.dropped_awaiting_key_frame++;
				WantKeyFrameLocked(input);
				return;
			}

			input->waiting_for_key_frame = false;
		}

		if (queued_bytes_ + frame->size > kMaxQueuedBytes || AllocationFault::CheckQueueRoom()) {
			// The writer does not keep up. Video goes on from the next key
			// frame, since everything up to it could not be decoded anyway.
			input->stats.dropped_queue_full++;

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
			// Dropped like a frame the queue has no room for: video goes on
			// from the next key frame.
			input->stats.dropped_allocation_failed++;

			if (video) {
				input->waiting_for_key_frame = true;
				WantKeyFrameLocked(input);
			}

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

		// Counted once it is in the queue, which may fail to grow.
		AllocationFault::CheckAllocation();

		queue_.push_back(std::move(recorded));

		input->last_time_us = frame->time_us;
		queued_bytes_ += frame->size;
		max_queued_bytes_ = std::max(max_queued_bytes_, queued_bytes_);

		wake_.notify_one();
	}

	void MediaRecorder::Dropped(Input * input, bool video)
	{
		std::lock_guard<std::mutex> lock(mutex_);

		input->stats.dropped_allocation_failed++;

		if (video) {
			input->waiting_for_key_frame = true;
			WantKeyFrameLocked(input);
		}
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
		// An exception that left the thread would end the process. Whatever
		// the loop held, such as the frames it had taken from the queue, and
		// the lock, is released on the way out.
		try {
			Record();
		}
		catch (...) {
			Abort();
		}
	}

	void MediaRecorder::Record()
	{
		std::deque<RecordedFrame> batch;
		std::vector<std::string> warnings;
		std::vector<int> key_frame_requests;
		std::vector<SaveRequest> saves;

		std::unique_lock<std::mutex> lock(mutex_);

		while (true) {
			wake_.wait_for(lock, kWriterTick, [this] {
				return !queue_.empty() || stopping_ || poke_;
			});

			poke_ = false;

			batch.swap(queue_);
			queued_bytes_ = 0;
			warnings.swap(pending_warnings_);
			saves.swap(save_requests_);

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

			const int64_t now_us = api_->now_us();

			if (mode_ == Mode::kReplay) {
				// After the frames that came before the request.
				for (const SaveRequest & save : saves) {
					Save(save);
				}

				saves.clear();

				RequestReplayKeyFrames(now_us);
			}
			else {
				// Nothing may arrive for a track that is waited for, so the
				// clock is looked at here as well.
				MaybeWriteHeader(false);

				if (mode_ == Mode::kSegments) {
					CheckSegments(now_us);
				}
			}

			lock.lock();

			if (stopping && queue_.empty() && save_requests_.empty()) {
				break;
			}
		}

		writer_ended_ = true;

		lock.unlock();

		Finish();
	}

	void MediaRecorder::Handle(RecordedFrame frame)
	{
		if (failed_) {
			return;
		}

		// Stands for what handling a frame may allocate, such as describing
		// its track or holding it back for the header.
		AllocationFault::CheckWriterAllocation();

		RecorderTrack & track = *tracks_[frame.track];

		if (track.GetStatus() == RecorderTrack::Status::kReady && frame.mime_type != track.GetMimeType()) {
			// Only segments and replays get here: the frames of a single file
			// keep the codec they began with.
			ChangeCodec(track, frame);
		}

		if (track.GetStatus() == RecorderTrack::Status::kPending) {
			std::string message;
			RecorderTrack::Status status = track.Probe(frame, format_, &message);

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
			if (awaiting_segment_[frame.track] && !rotation_due_) {
				// Described with its new codec, which goes into a segment of
				// its own.
				DueRotation();
			}
		}

		if (track.GetStatus() != RecorderTrack::Status::kReady) {
			return;
		}

		track.Timestamp(frame, start_us_);

		if (mode_ == Mode::kReplay) {
			Buffer(std::move(frame));
			return;
		}

		if (!header_written_) {
			pending_bytes_ += static_cast<size_t>(frame.packet->size);
			pending_.push_back(std::move(frame));

			MaybeWriteHeader(false);
			return;
		}

		Route(frame);
	}

	void MediaRecorder::ChangeCodec(RecorderTrack & track, const RecordedFrame & frame)
	{
		const int index = track.GetIndex();

		Warn("Track " + std::to_string(index) + " changed its codec from " + track.GetMimeType()
				+ " to " + frame.mime_type
				+ (mode_ == Mode::kSegments ? ", and goes on in the next segment" : ""));

		track.Reset();

		if (mode_ == Mode::kReplay) {
			// A stream in a file cannot change its codec, and a replay holds
			// the new one.
			RemoveFromBuffer(index);
		}
		else if (!header_written_) {
			pending_.erase(std::remove_if(pending_.begin(), pending_.end(),
					[this, index](const RecordedFrame & pending) {
						if (pending.track != index) {
							return false;
						}

						pending_bytes_ -= static_cast<size_t>(pending.packet->size);
						return true;
					}), pending_.end());
		}
		else {
			awaiting_segment_[index] = true;
		}
	}

	void MediaRecorder::Route(RecordedFrame & frame)
	{
		if (mode_ == Mode::kSegments) {
			RouteSegment(frame);
		}
		// A track that got ready only after the header has no stream.
		else if (output_->HasStream(frame.track)) {
			Write(*output_, frame);
		}
	}

	void MediaRecorder::RouteSegment(RecordedFrame & frame)
	{
		const int index = frame.track;
		const bool video = tracks_[index]->IsVideo();

		if (rotation_due_) {
			// A segment begins with a key frame of a video track. With no
			// video, or none that sends one in time, it begins anywhere.
			if ((video && frame.key_frame) || !AnyVideoReady()
					|| api_->now_us() - rotation_due_us_ > kRotationWaitUs) {
				Rotate(frame);

				if (failed_) {
					return;
				}
			}
		}

		if (awaiting_segment_[index]) {
			return;
		}

		if (video && !switched_[index]) {
			// Until its own key frame, a video track goes on in the segment
			// before, so that both play without a gap.
			if (frame.key_frame && output_->HasStream(index)) {
				switched_[index] = true;

				Write(*output_, frame);

				if (draining_ != nullptr && !Draining()) {
					FinishSegment(std::move(draining_));
				}
			}
			else if (draining_ != nullptr && draining_->HasStream(index)) {
				Write(*draining_, frame);
			}
			else if (output_->HasStream(index)) {
				CountAwaitingKeyFrame(index);
			}
		}
		else if (output_->HasStream(index)) {
			Write(*output_, frame);
		}

		// A request still held back from while the rotation was due is
		// answered by this key frame.
		if (video && frame.key_frame && switched_[index] && !rotation_due_) {
			KeyFrameArrived(index);
		}

		if (!failed_ && !rotation_due_ && SegmentLimitReached()) {
			DueRotation();
		}
	}

	void MediaRecorder::Rotate(const RecordedFrame & frame)
	{
		// A segment that still drains when the next one is due is done.
		FinishSegment(std::move(draining_));

		if (failed_) {
			return;
		}

		draining_ = std::move(output_);

		if (!OpenSegment(frame.timeline_us)) {
			FinishSegment(std::move(draining_));
			return;
		}

		for (size_t i = 0; i < tracks_.size(); i++) {
			switched_[i] = !tracks_[i]->IsVideo()
					|| (static_cast<int>(i) == frame.track && frame.key_frame);

			// A track that changed its codec and is not described anew yet
			// waits for the segment after.
			awaiting_segment_[i] = awaiting_segment_[i] && !output_->HasStream(static_cast<int>(i));
		}

		rotation_due_ = false;
		drain_started_us_ = api_->now_us();

		if (!Draining()) {
			FinishSegment(std::move(draining_));
		}
	}

	void MediaRecorder::DueRotation()
	{
		rotation_due_ = true;
		rotation_due_us_ = api_->now_us();

		RequestKeyFrames();
	}

	bool MediaRecorder::OpenSegment(int64_t start_us)
	{
		for (int skipped = 0; ; skipped++) {
			const int64_t index = next_index_++;
			auto segment = std::make_unique<RecorderOutput>(SegmentPath(index), start_us, index);
			const std::string & path = segment->GetPath();

			int result = segment->Open(true);

			if (result == AVERROR(EEXIST) && skipped < kMaxSkippedSegments) {
				// Left as it is; reported once, not for every file in a row.
				if (!skipped_existing_) {
					skipped_existing_ = true;
					Warn(path + " exists already, so the segment goes into the next free file");
				}
				continue;
			}
			if (result < 0) {
				// Nothing to delete: the file was not opened, or is not ours.
				Fail("Opening " + path + " failed", result);
				return false;
			}

			for (const auto & track : tracks_) {
				if (track->GetStatus() == RecorderTrack::Status::kReady) {
					result = segment->AddStream(*track);

					if (result < 0) {
						Fail("Adding track " + std::to_string(track->GetIndex()) + " to " + path + " failed", result);
						break;
					}
				}
			}

			if (result >= 0) {
				result = segment->WriteHeader();

				if (result < 0) {
					Fail("Writing the header of " + path + " failed", result);
				}
			}

			if (result < 0) {
				// Closed first, since a file that is open cannot be deleted
				// everywhere. Its failure is the one reported.
				segment->Close();

				if (observer_ != nullptr) {
					observer_->OnSegmentFinished(index, path, start_us, 0,
							static_cast<int>(Result::kFailed));
				}
				return false;
			}

			output_ = std::move(segment);

			return true;
		}
	}

	void MediaRecorder::FinishSegment(std::unique_ptr<RecorderOutput> segment)
	{
		if (segment == nullptr) {
			return;
		}

		// Even after a failed write: whatever made it into the file is only
		// playable with a trailer. After an earlier failure, the same cause
		// is not reported twice.
		int result = segment->WriteTrailer();

		if (result < 0) {
			if (!failed_) {
				Fail("Finishing " + segment->GetPath() + " failed", result);
			}
			segment->SetFailed();
		}

		result = segment->Close();

		if (result < 0) {
			if (!failed_) {
				Fail("Closing " + segment->GetPath() + " failed", result);
			}
			segment->SetFailed();
		}

		Result outcome = Result::kFailed;

		if (segment->HasMedia()) {
			outcome = segment->IsFailed() ? Result::kPartial : Result::kComplete;
		}

		if (observer_ != nullptr) {
			observer_->OnSegmentFinished(segment->GetIndex(), segment->GetPath(),
					segment->GetStartUs(), segment->GetDurationUs(), static_cast<int>(outcome));
		}
	}

	void MediaRecorder::CheckSegments(int64_t now_us)
	{
		if (failed_ || output_ == nullptr) {
			return;
		}

		// A video track that sends no key frame in time waits for it with
		// nothing recorded, rather than hold the segment before open.
		if (draining_ != nullptr && now_us - drain_started_us_ > kDrainUs) {
			FinishSegment(std::move(draining_));

			if (failed_) {
				return;
			}
		}

		std::lock_guard<std::mutex> lock(mutex_);

		for (size_t i = 0; i < tracks_.size(); i++) {
			const RecorderTrack & track = *tracks_[i];

			if (track.GetStatus() != RecorderTrack::Status::kReady || !track.IsVideo()) {
				continue;
			}

			// Asked again as long as they are waited for; the requests go out
			// no more than once a second.
			if (rotation_due_ || (!switched_[i] && output_->HasStream(static_cast<int>(i)))) {
				WantKeyFrameLocked(inputs_[i].get());
			}
		}
	}

	bool MediaRecorder::Draining() const
	{
		for (size_t i = 0; i < tracks_.size(); i++) {
			if (!switched_[i] && !awaiting_segment_[i] && draining_->HasStream(static_cast<int>(i))) {
				return true;
			}
		}

		return false;
	}

	std::string MediaRecorder::SegmentPath(int64_t index) const
	{
		std::string digits = std::to_string(index);

		if (digits.size() < static_cast<size_t>(segments_.width)) {
			digits.insert(0, static_cast<size_t>(segments_.width) - digits.size(), '0');
		}

		return segments_.prefix + digits + segments_.suffix;
	}

	void MediaRecorder::RequestKeyFrames()
	{
		std::lock_guard<std::mutex> lock(mutex_);

		for (size_t i = 0; i < tracks_.size(); i++) {
			const RecorderTrack & track = *tracks_[i];

			if (track.GetStatus() == RecorderTrack::Status::kReady && track.IsVideo()) {
				WantKeyFrameLocked(inputs_[i].get());
			}
		}
	}

	void MediaRecorder::KeyFrameArrived(int track)
	{
		std::lock_guard<std::mutex> lock(mutex_);

		Input * input = inputs_[track].get();

		// A track that waits for a key frame again, having dropped frames
		// since this one was queued, still needs another.
		if (!input->waiting_for_key_frame) {
			input->key_frame_wanted = false;
		}
	}

	void MediaRecorder::CountAwaitingKeyFrame(int track)
	{
		std::lock_guard<std::mutex> lock(mutex_);

		inputs_[track]->stats.dropped_awaiting_key_frame++;
	}

	bool MediaRecorder::AnyVideoReady() const
	{
		for (const auto & track : tracks_) {
			if (track->GetStatus() == RecorderTrack::Status::kReady && track->IsVideo()) {
				return true;
			}
		}

		return false;
	}

	bool MediaRecorder::SegmentLimitReached() const
	{
		if (segments_.max_duration_us > 0 && output_->GetDurationUs() >= segments_.max_duration_us) {
			return true;
		}

		return segments_.max_bytes > 0 && output_->GetBytes() >= segments_.max_bytes;
	}

	void MediaRecorder::Buffer(RecordedFrame frame)
	{
		const int index = frame.track;

		if (tracks_[index]->IsVideo() && frame.key_frame) {
			key_times_[index].push_back(frame.timeline_us);
			last_key_us_[index] = api_->now_us();

			// The clock asked for it until it came, and a request still held
			// back would bring another one before its time.
			KeyFrameArrived(index);
		}

		last_frame_us_[index] = frame.timeline_us;
		ring_bytes_ += static_cast<size_t>(frame.packet->size);
		ring_.push_back(std::move(frame));

		if (!header_written_) {
			header_written_ = true;

			if (observer_ != nullptr) {
				observer_->OnStarted();
			}
		}

		TrimBuffer();
	}

	void MediaRecorder::TrimBuffer()
	{
		const int64_t cutoff = ring_.back().timeline_us - replay_.window_us;
		int64_t keep_from = cutoff;

		// Every video track that still sends needs the key frame its part of
		// the window decodes from: the last one before the window, or the
		// first one in it.
		for (size_t i = 0; i < tracks_.size(); i++) {
			const std::deque<int64_t> & keys = key_times_[i];

			if (keys.empty() || last_frame_us_[i] < cutoff) {
				continue;
			}

			int64_t from = keys.front();

			for (int64_t key : keys) {
				if (key > cutoff) {
					break;
				}
				from = key;
			}

			keep_from = std::min(keep_from, from);
		}

		// Past its limit, the buffer lets go of the oldest frames whatever
		// they are; a replay then begins at the next key frame.
		while (!ring_.empty() && (ring_.front().timeline_us < keep_from
				|| ring_bytes_ > static_cast<size_t>(replay_.max_bytes))) {
			const RecordedFrame & front = ring_.front();
			const int index = front.track;

			if (front.key_frame && tracks_[index]->IsVideo() && !key_times_[index].empty()) {
				key_times_[index].pop_front();

				// What follows of the track decodes from nothing, so it waits
				// for the next key frame, and asks for it.
				if (key_times_[index].empty()) {
					RestartAtKeyFrame(index);
				}
			}

			ring_bytes_ -= static_cast<size_t>(front.packet->size);
			ring_.pop_front();
		}
	}

	void MediaRecorder::RemoveFromBuffer(int track)
	{
		ring_.erase(std::remove_if(ring_.begin(), ring_.end(),
				[this, track](const RecordedFrame & frame) {
					if (frame.track != track) {
						return false;
					}

					ring_bytes_ -= static_cast<size_t>(frame.packet->size);
					return true;
				}), ring_.end());

		key_times_[track].clear();
		last_frame_us_[track] = std::numeric_limits<int64_t>::min();
	}

	void MediaRecorder::RequestReplayKeyFrames(int64_t now_us)
	{
		if (failed_) {
			return;
		}

		std::lock_guard<std::mutex> lock(mutex_);

		for (size_t i = 0; i < tracks_.size(); i++) {
			const RecorderTrack & track = *tracks_[i];

			// WebRTC sends key frames rarely of its own accord, and without
			// them the buffer could let go of nothing.
			if (track.GetStatus() == RecorderTrack::Status::kReady && track.IsVideo()
					&& now_us - last_key_us_[i] >= key_frame_interval_us_) {
				WantKeyFrameLocked(inputs_[i].get());
			}
		}
	}

	void MediaRecorder::Save(const SaveRequest & request)
	{
		if (observer_ == nullptr) {
			return;
		}
		if (ring_.empty()) {
			observer_->OnReplaySaved(request.id, static_cast<int>(Result::kEmpty), 0, 0, std::string());
			return;
		}

		const size_t count = tracks_.size();
		const int64_t cutoff = ring_.back().timeline_us - replay_.window_us;
		const int64_t none = std::numeric_limits<int64_t>::max();

		// Where each track begins: a video track at the key frame its part of
		// the window decodes from, the others where the earliest of those is.
		std::vector<int64_t> begin(count, none);
		int64_t start = none;

		for (size_t i = 0; i < count; i++) {
			const std::deque<int64_t> & keys = key_times_[i];

			if (!tracks_[i]->IsVideo() || keys.empty() || last_frame_us_[i] < cutoff) {
				continue;
			}

			int64_t from = keys.front();

			for (int64_t key : keys) {
				if (key > cutoff) {
					break;
				}
				from = key;
			}

			begin[i] = from;
			start = std::min(start, from);
		}

		if (start == none) {
			start = std::max(cutoff, ring_.front().timeline_us);
		}

		for (size_t i = 0; i < count; i++) {
			if (!tracks_[i]->IsVideo()) {
				begin[i] = start;
			}
		}

		std::vector<bool> has_frames(count, false);

		for (const RecordedFrame & frame : ring_) {
			if (frame.timeline_us >= begin[frame.track]) {
				has_frames[frame.track] = true;
			}
		}

		RecorderOutput output(request.path, start, request.id);
		const std::string & path = output.GetPath();

		int result = output.Open(false);

		if (result < 0) {
			SaveFailed(request, "Opening " + path + " failed", result);
			return;
		}

		for (size_t i = 0; i < count; i++) {
			std::string message;

			if (!has_frames[i] || tracks_[i]->GetStatus() != RecorderTrack::Status::kReady) {
				continue;
			}
			if (!tracks_[i]->CanBeHeldBy(output.GetFormat(), &message)) {
				Warn(message);
				continue;
			}

			result = output.AddStream(*tracks_[i]);

			if (result < 0) {
				output.Close();
				SaveFailed(request, "Adding track " + std::to_string(i) + " to " + path + " failed", result);
				return;
			}
		}

		if (!output.HasStreams()) {
			output.Close();
			observer_->OnReplaySaved(request.id, static_cast<int>(Result::kEmpty), 0, 0, std::string());
			return;
		}

		result = output.WriteHeader();

		if (result < 0) {
			output.Close();
			SaveFailed(request, "Writing the header of " + path + " failed", result);
			return;
		}

		std::vector<bool> begun(count, false);
		std::string error;

		for (RecordedFrame & frame : ring_) {
			const int index = frame.track;

			if (!output.HasStream(index) || frame.timeline_us < begin[index]) {
				continue;
			}
			if (tracks_[index]->IsVideo() && !begun[index]) {
				if (!frame.key_frame) {
					continue;
				}
				begun[index] = true;
			}

			// The buffer keeps the frame for the replays to come.
			result = output.Write(frame, true);

			if (result < 0) {
				error = "Writing to " + path + " failed: " + ErrorText(result);
				break;
			}
		}

		// Even after a failed write: whatever made it into the file is only
		// playable with a trailer.
		result = output.WriteTrailer();

		if (result < 0 && error.empty()) {
			error = "Finishing " + path + " failed: " + ErrorText(result);
		}

		result = output.Close();

		if (result < 0 && error.empty()) {
			error = "Closing " + path + " failed: " + ErrorText(result);
		}

		Result outcome;

		if (output.HasMedia()) {
			outcome = error.empty() ? Result::kComplete : Result::kPartial;
		}
		else {
			outcome = error.empty() ? Result::kEmpty : Result::kFailed;
		}

		observer_->OnReplaySaved(request.id, static_cast<int>(outcome), start, output.GetDurationUs(), error);
	}

	void MediaRecorder::SaveFailed(const SaveRequest & request, const std::string & message, int error)
	{
		observer_->OnReplaySaved(request.id, static_cast<int>(Result::kFailed), 0, 0,
				message + ": " + ErrorText(error));
	}

	void MediaRecorder::Write(RecorderOutput & output, RecordedFrame & frame)
	{
		int result = output.Write(frame, false);

		if (result < 0) {
			Fail("Writing to " + output.GetPath() + " failed", result, &output);
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
			if (track->GetStatus() == RecorderTrack::Status::kPending) {
				Warn("Track " + std::to_string(track->GetIndex())
						+ " had no media to record when the recording began, and is left out"
						+ (mode_ == Mode::kSegments ? " until the next segment" : ""));
			}
		}

		if (mode_ == Mode::kSegments) {
			// The first segment begins with the earliest frame held back,
			// which is a key frame for every video track.
			int64_t start = std::numeric_limits<int64_t>::max();

			for (const RecordedFrame & frame : pending_) {
				start = std::min(start, frame.timeline_us);
			}

			if (!OpenSegment(start)) {
				pending_.clear();
				return false;
			}
		}
		else {
			for (const auto & track : tracks_) {
				if (track->GetStatus() == RecorderTrack::Status::kReady) {
					int result = output_->AddStream(*track);

					if (result < 0) {
						Fail("Adding track " + std::to_string(track->GetIndex()) + " to the file failed", result);
						pending_.clear();
						return false;
					}
				}
			}

			int result = output_->WriteHeader();

			if (result < 0) {
				Fail("Writing the header of " + path_ + " failed", result);
				pending_.clear();
				return false;
			}
		}

		header_written_ = true;

		if (observer_ != nullptr) {
			observer_->OnStarted();
		}

		while (!pending_.empty() && !failed_) {
			RecordedFrame frame = std::move(pending_.front());
			pending_.pop_front();

			Route(frame);
		}

		pending_.clear();
		pending_bytes_ = 0;

		return true;
	}

	void MediaRecorder::Finish()
	{
		if (mode_ == Mode::kReplay) {
			ring_.clear();
			ring_bytes_ = 0;
			return;
		}

		MaybeWriteHeader(true);

		pending_.clear();

		if (mode_ == Mode::kSegments) {
			FinishSegment(std::move(draining_));
			FinishSegment(std::move(output_));
			return;
		}

		if (header_written_) {
			// Even after a failed write: whatever made it into the file
			// is only playable with a trailer.
			int result = output_->WriteTrailer();

			if (result < 0 && !failed_) {
				Fail("Finishing " + path_ + " failed", result);
			}
		}

		Close();
	}

	void MediaRecorder::Close()
	{
		if (output_ == nullptr) {
			return;
		}

		int result = output_->Close();

		// After an earlier failure, the same cause is not reported twice.
		if (result < 0 && !failed_) {
			Fail("Closing " + path_ + " failed", result);
		}
	}

	void MediaRecorder::Fail(const std::string & message, int error, RecorderOutput * output)
	{
		failed_ = true;

		if (output != nullptr) {
			output->SetFailed();
		}

		{
			std::lock_guard<std::mutex> lock(mutex_);

			// Nothing more goes into the file, so nothing needs copying.
			accepting_ = false;
		}

		if (observer_ != nullptr) {
			observer_->OnError(message + ": " + ErrorText(error));
		}
	}

	void MediaRecorder::Abort() noexcept
	{
		// Nothing more goes into the file, and what waits for it is let go
		// now rather than once the recorder is stopped, which may be a while.
		// A recording that had failed already was reported then.
		const bool reported = failed_;

		failed_ = true;

		std::deque<RecordedFrame> dropped;
		std::vector<SaveRequest> saves;

		try {
			std::lock_guard<std::mutex> lock(mutex_);

			accepting_ = false;
			writer_ended_ = true;
			queue_.swap(dropped);
			queued_bytes_ = 0;
			save_requests_.swap(saves);
		}
		catch (...) {
			// Without the lock the callbacks go on queueing, up to the limit,
			// until the recorder is stopped.
		}

		dropped.clear();
		pending_.clear();
		pending_bytes_ = 0;
		ring_.clear();
		ring_bytes_ = 0;

		const std::string * message = &out_of_memory_error_;
		std::string described;

		try {
			throw;
		}
		catch (const std::bad_alloc &) {
		}
		catch (const std::exception & e) {
			try {
				described = subject_ + " failed: " + e.what();
				message = &described;
			}
			catch (...) {
			}
		}
		catch (...) {
			try {
				described = subject_ + " failed";
				message = &described;
			}
			catch (...) {
			}
		}

		try {
			if (observer_ != nullptr && !reported) {
				observer_->OnError(*message);
			}
		}
		catch (...) {
		}

		// Replays asked for and not saved are answered, so that nobody waits
		// for them until the stop.
		for (const SaveRequest & save : saves) {
			try {
				if (observer_ != nullptr) {
					observer_->OnReplaySaved(save.id, static_cast<int>(Result::kFailed), 0, 0, *message);
				}
			}
			catch (...) {
			}
		}

		// Once failed, finishing writes no more media and reports nothing,
		// so it needs no memory: the trailer keeps what is in the file
		// playable. Should it throw all the same, Stop() still closes the
		// file.
		try {
			Finish();
		}
		catch (...) {
		}
	}

	void MediaRecorder::Warn(const std::string & message)
	{
		if (observer_ != nullptr) {
			observer_->OnWarning(message);
		}
	}
}
