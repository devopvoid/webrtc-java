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

#include "media/MediaPlayer.h"
#include "media/ErrorText.h"

#include <chrono>
#include <utility>

extern "C" {
#include <libavutil/error.h>
}

namespace ffmpeg
{
	MediaPlayer::MediaPlayer(std::unique_ptr<MediaReader> reader,
			const webrtc_java_api * api, void * video_source, void * audio_source)
		: reader_(std::move(reader)),
		  api_(api)
	{
		pacer_ = std::make_unique<MediaPacer>(api, video_source, audio_source);
	}

	MediaPlayer::~MediaPlayer()
	{
		Close();
	}

	void MediaPlayer::SetObserver(std::unique_ptr<MediaPlayerObserver> observer)
	{
		std::lock_guard<std::mutex> lock(mutex_);

		observer_ = std::move(observer);
	}

	int MediaPlayer::Initialize()
	{
		if (reader_ == nullptr || !reader_->IsOpen()) {
			return AVERROR(EINVAL);
		}

		if (reader_->HasVideo()) {
			int result = video_decoder_.Open(reader_->GetVideoStream());

			if (result < 0) {
				return result;
			}

			has_video_ = true;
		}

		if (reader_->HasAudio()) {
			int result = audio_decoder_.Open(reader_->GetAudioStream());

			if (result < 0) {
				return result;
			}

			has_audio_ = true;
		}

		if (!has_video_ && !has_audio_) {
			return AVERROR_STREAM_NOT_FOUND;
		}

		pacer_->Start();

		{
			std::lock_guard<std::mutex> lock(mutex_);

			running_ = true;
			thread_ = std::thread(&MediaPlayer::Run, this);
		}

		return 0;
	}

	void MediaPlayer::Play()
	{
		bool changed = false;

		{
			std::lock_guard<std::mutex> lock(mutex_);

			if (closing_ || playing_) {
				return;
			}

			playing_ = true;

			if (ended_) {
				// Playing again after the source ran out starts it over,
				// which is what a listener would expect of a play button.
				ended_ = false;
				seek_pending_ = true;
				seek_position_us_ = 0;
			}

			// Under the lock, together with the flag: the decode thread stops
			// playback on an error, and the two must not interleave.
			pacer_->Resume();
			changed = UpdateStateLocked(kPlaying);

			command_.notify_all();
		}

		if (changed) {
			NotifyState(kPlaying);
		}
	}

	void MediaPlayer::Pause()
	{
		bool changed = false;

		{
			std::lock_guard<std::mutex> lock(mutex_);

			if (closing_ || !playing_) {
				return;
			}

			playing_ = false;

			pacer_->Pause();
			changed = UpdateStateLocked(kPaused);
		}

		if (changed) {
			NotifyState(kPaused);
		}
	}

	void MediaPlayer::Seek(int64_t position_us)
	{
		{
			std::lock_guard<std::mutex> lock(mutex_);

			if (closing_) {
				return;
			}

			seek_pending_ = true;
			seek_position_us_ = position_us;
			ended_ = false;

			command_.notify_all();
		}

		// The decode thread may be held up by a full queue, and it cannot act
		// on the request until it gets out of that. Dropping what is queued
		// both frees it and throws away what belongs to the old position; the
		// thread flushes again once the seek has actually happened.
		pacer_->Flush();
	}

	void MediaPlayer::SetLooping(bool looping)
	{
		looping_.store(looping);
	}

	bool MediaPlayer::IsLooping() const
	{
		return looping_.load();
	}

	void MediaPlayer::Close()
	{
		{
			std::lock_guard<std::mutex> lock(mutex_);

			if (closing_) {
				return;
			}

			closing_ = true;
			playing_ = false;

			command_.notify_all();
		}

		// A decode thread blocked reading a source that has gone quiet, such
		// as a stalled network stream, would otherwise hold up the join below
		// until the read timed out.
		if (reader_ != nullptr) {
			reader_->Interrupt();
		}

		// Stopping the pacer is what releases a decode thread waiting for room
		// in a queue that nothing is draining any more.
		pacer_->Stop();

		if (thread_.joinable()) {
			thread_.join();
		}

		video_decoder_.Close();
		audio_decoder_.Close();

		if (reader_ != nullptr) {
			reader_->Close();
		}

		SetState(kClosed);
	}

	int64_t MediaPlayer::GetPositionUs() const
	{
		const int64_t position = pacer_->GetPositionUs() - loop_offset_us_.load();

		return position > 0 ? position : 0;
	}

	int MediaPlayer::GetState() const
	{
		std::lock_guard<std::mutex> lock(mutex_);

		return state_;
	}

	void MediaPlayer::Run()
	{
		AVPacket * packet = av_packet_alloc();

		if (packet == nullptr) {
			ReportError("Allocating a packet failed", AVERROR(ENOMEM));

			return;
		}

		for (;;) {
			int64_t seek_to = 0;
			bool do_seek = false;

			{
				std::unique_lock<std::mutex> lock(mutex_);

				command_.wait(lock, [this] {
					return closing_ || seek_pending_ || (playing_ && !ended_);
				});

				if (closing_) {
					break;
				}

				if (seek_pending_) {
					seek_pending_ = false;
					seek_to = seek_position_us_;
					do_seek = true;
				}
			}

			if (do_seek) {
				PerformSeek(seek_to);

				continue;
			}

			bool end_of_stream = false;

			if (!PumpOnce(packet, &end_of_stream)) {
				// Playback has stopped and been reported. The thread stays,
				// so that playing again carries on past what failed, and a
				// seek can move away from it.
				continue;
			}

			if (!end_of_stream) {
				continue;
			}

			// Whatever the decoders still hold belongs to this pass, so it
			// goes out before anything is rewound.
			DrainDecoders();

			// A source that cannot be rewound, such as a live stream that has
			// ended, cannot start over either, and ends as if not looping.
			if (looping_.load() && reader_->Seek(0) >= 0) {
				int64_t advance = reader_->GetDurationUs();

				if (advance <= 0) {
					// A container that does not say how long it runs still
					// tells us where its last frame was.
					advance = max_source_pts_us_ + 10000;
				}

				loop_offset_us_.fetch_add(advance);

				video_decoder_.Flush();
				audio_decoder_.Flush();

				// The pacer keeps its queue and its mapping, so the seam
				// between one pass and the next is not heard or seen.
				continue;
			}

			// Everything has been handed over, but not yet played out.
			for (;;) {
				std::unique_lock<std::mutex> lock(mutex_);

				if (closing_ || pacer_->IsDrained()) {
					break;
				}

				command_.wait_for(lock, std::chrono::milliseconds(10));
			}

			{
				std::lock_guard<std::mutex> lock(mutex_);

				if (closing_) {
					break;
				}

				ended_ = true;
				playing_ = false;
			}

			SetState(kEnded);

			MediaPlayerObserver * observer = nullptr;
			{
				std::lock_guard<std::mutex> lock(mutex_);

				observer = observer_.get();
			}

			if (observer != nullptr) {
				observer->OnEndOfStream();
			}
		}

		av_packet_free(&packet);
	}

	bool MediaPlayer::PumpOnce(AVPacket * packet, bool * end_of_stream)
	{
		int result = reader_->ReadPacket(packet);

		if (result == AVERROR_EOF) {
			*end_of_stream = true;

			return true;
		}
		if (result < 0) {
			ReportError("Reading the source failed", result);

			return false;
		}

		const int index = packet->stream_index;
		bool video = has_video_ && index == reader_->GetVideoStreamIndex();
		bool audio = has_audio_ && index == reader_->GetAudioStreamIndex();

		if (video) {
			result = video_decoder_.SendPacket(packet);
		}
		else if (audio) {
			result = audio_decoder_.SendPacket(packet);
		}
		else {
			// A stream this player does not play, such as a subtitle track.
			result = 0;
		}

		av_packet_unref(packet);

		if (result < 0 && result != AVERROR(EAGAIN)) {
			ReportError("Decoding failed", result);

			return false;
		}

		if (video) {
			return DecodeVideo() >= 0;
		}
		if (audio) {
			return DecodeAudio() >= 0;
		}

		return true;
	}

	void MediaPlayer::DrainDecoders()
	{
		if (has_video_) {
			video_decoder_.SendPacket(nullptr);
			DecodeVideo();
		}
		if (has_audio_) {
			audio_decoder_.SendPacket(nullptr);
			DecodeAudio();
		}
	}

	int MediaPlayer::DecodeVideo()
	{
		for (;;) {
			AVFrame * frame = nullptr;
			int64_t timestamp_us = 0;

			int result = video_decoder_.ReceiveFrame(&frame, &timestamp_us);

			if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) {
				return 0;
			}
			if (result < 0) {
				ReportError("Decoding video failed", result);

				return result;
			}

			if (timestamp_us > max_source_pts_us_) {
				max_source_pts_us_ = timestamp_us;
			}

			// The frame belongs to the pacer from here on, whether or not it
			// makes it into the queue.
			if (!pacer_->PushVideo(frame, timestamp_us + loop_offset_us_.load())) {
				return 0;
			}
		}
	}

	int MediaPlayer::DecodeAudio()
	{
		for (;;) {
			std::vector<int16_t> chunk;
			int64_t timestamp_us = 0;

			int result = audio_decoder_.ReceiveChunk(chunk, &timestamp_us);

			if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) {
				return 0;
			}
			if (result < 0) {
				ReportError("Decoding audio failed", result);

				return result;
			}

			if (timestamp_us > max_source_pts_us_) {
				max_source_pts_us_ = timestamp_us;
			}

			if (!pacer_->PushAudio(std::move(chunk), audio_decoder_.GetChannels(),
					timestamp_us + loop_offset_us_.load())) {
				return 0;
			}
		}
	}

	void MediaPlayer::PerformSeek(int64_t position_us)
	{
		int result = reader_->Seek(position_us);

		if (result < 0) {
			ReportError("Seeking failed", result);

			return;
		}

		video_decoder_.Flush();
		audio_decoder_.Flush();

		// A seek starts a timeline of its own: the pacer maps the first item
		// that arrives onto the clock afresh, so the offset that kept looping
		// monotonic is no longer needed and would only skew the position.
		loop_offset_us_.store(0);
		max_source_pts_us_ = position_us;

		pacer_->Flush();
	}

	void MediaPlayer::SetState(int state)
	{
		bool changed = false;

		{
			std::lock_guard<std::mutex> lock(mutex_);

			changed = UpdateStateLocked(state);
		}

		if (changed) {
			NotifyState(state);
		}
	}

	bool MediaPlayer::UpdateStateLocked(int state)
	{
		if (state_ == state) {
			return false;
		}

		state_ = state;

		return true;
	}

	void MediaPlayer::NotifyState(int state)
	{
		// Called with the lock released: an observer runs Java code, which
		// must never happen underneath a lock of ours. The observer does not
		// change once the thread runs, so it is read without the lock.
		if (observer_ != nullptr) {
			observer_->OnStateChanged(state);
		}
	}

	void MediaPlayer::ReportError(const std::string & message, int error)
	{
		bool changed = false;

		{
			std::lock_guard<std::mutex> lock(mutex_);

			// A player being closed fails whatever it was waiting on on
			// purpose, since closing interrupts the reader. That is not an
			// error of the source, and nobody should hear of it.
			if (closing_) {
				return;
			}

			// Playback stops where it failed, as if paused: what is queued
			// stays queued, and playing again carries on from here.
			if (playing_) {
				playing_ = false;

				pacer_->Pause();
				changed = UpdateStateLocked(kPaused);
			}
		}

		if (changed) {
			NotifyState(kPaused);
		}

		if (observer_ != nullptr) {
			observer_->OnError(message + ": " + ErrorText(error));
		}
	}
}
