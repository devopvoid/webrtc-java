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

#ifndef WEBRTC_JAVA_MEDIA_MEDIA_RECORDER_H_
#define WEBRTC_JAVA_MEDIA_MEDIA_RECORDER_H_

#include "media/MediaRecorderObserver.h"
#include "media/RecorderTrack.h"
#include "webrtc_java_api.h"

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

extern "C" {
#include <libavformat/avformat.h>
}

namespace ffmpeg
{
	// Records the encoded frames of RTCRtpSenders and RTCRtpReceivers into a
	// media file, as they are: nothing is decoded or encoded again, so a
	// recording costs a copy of each frame and the writing, nothing more.
	//
	// Frames arrive through webrtc-java's encoded frame observers, on the
	// WebRTC threads that carry media. There they are only checked and copied
	// into a queue, and a thread of the recorder's own writes them out, so
	// that a slow disk never holds up a call.
	//
	// The file header describes every stream, and a stream is only known once
	// its first frames arrived (for video, its first key frame). So the
	// header is written once every track has described itself, or once the
	// tracks that did have waited long enough for the rest; frames that come
	// before are held back until then.
	class MediaRecorder
	{
		public:
			MediaRecorder(const webrtc_java_api * api, std::string path);

			// Stops the recording if it still runs.
			~MediaRecorder();

			MediaRecorder(const MediaRecorder &) = delete;
			MediaRecorder & operator=(const MediaRecorder &) = delete;

			// Takes over the observer. Call before Start().
			void SetObserver(std::unique_ptr<MediaRecorderObserver> observer);

			// Opens the output file, choosing the container from its name.
			// Returns 0 or a negative AVERROR.
			int Open();

			// Adds a sender or receiver to record, by the handle that
			// NativeApi.encodedFramesOf() returned. The recorder takes over
			// that handle's reference. Returns the track index. Only before
			// Start().
			int AddTrack(void * frames);

			// Attaches to every track and starts writing.
			void Start();

			// Detaches from every track, writes out what is queued, finishes
			// the file and waits for the writer thread. Returns whether any
			// media was written. Stopping twice does nothing more.
			bool Stop();

		private:
			// What the observer callback knows about a track. Guarded by
			// mutex_, since WebRTC calls in on threads of its own.
			struct Input
			{
				MediaRecorder * recorder = nullptr;
				int index = 0;
				void * frames = nullptr;
				void * observer = nullptr;

				// Set by the first frame the track takes, which fixes the
				// stream it records: one SSRC (one simulcast layer) and one
				// codec.
				bool locked = false;
				uint32_t ssrc = 0;
				std::string mime_type;
				int64_t last_time_us = 0;

				// A video track takes nothing until it sees a key frame, at
				// the start and after it had to drop frames.
				bool waiting_for_key_frame = true;
				bool key_frame_wanted = false;
				int64_t last_key_frame_request_us = 0;

				bool reported_codec_change = false;
			};

			static void OnEncodedFrame(void * opaque, const wj_encoded_frame * frame);

			void Enqueue(Input * input, const wj_encoded_frame * frame);
			void WantKeyFrameLocked(Input * input);

			// Makes a video track wait for its next key frame, and asks for
			// one.
			void RestartAtKeyFrame(int track);

			void Run();

			void Handle(RecordedFrame frame);
			void Write(RecordedFrame & frame);

			// Writes the header once the tracks are known, or once waiting
			// for the rest is no longer worth it. Returns whether the header
			// is written.
			bool MaybeWriteHeader(bool finishing);

			void Finish();
			void Fail(const std::string & message, int error);
			void Warn(const std::string & message);

			// Called with the lock held; the requests are sent once it is
			// released.
			void CollectKeyFrameRequestsLocked(std::vector<int> * tracks, int64_t now_us);

			const webrtc_java_api * api_;
			const std::string path_;

			std::unique_ptr<MediaRecorderObserver> observer_;

			// The writer thread's, once Start() ran.
			AVFormatContext * context_ = nullptr;
			std::vector<std::unique_ptr<RecorderTrack>> tracks_;
			std::deque<RecordedFrame> pending_;
			size_t pending_bytes_ = 0;
			int64_t first_ready_us_ = 0;
			// Also read by Stop(), on the thread that stops.
			std::atomic<bool> header_written_{ false };
			bool failed_ = false;

			// When recording started, in the clock of the frames.
			int64_t start_us_ = 0;

			std::thread thread_;

			std::mutex mutex_;
			std::condition_variable wake_;
			std::vector<std::unique_ptr<Input>> inputs_;
			std::deque<RecordedFrame> queue_;
			size_t queued_bytes_ = 0;
			// What the callbacks have to report, which the writer does.
			std::vector<std::string> pending_warnings_;
			// Wakes the writer for something other than frames.
			bool poke_ = false;
			bool accepting_ = false;
			bool stopping_ = false;
			bool started_ = false;
			bool stopped_ = false;
	};
}

#endif
