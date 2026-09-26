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

#ifndef WEBRTC_JAVA_MEDIA_MEDIA_PLAYER_H_
#define WEBRTC_JAVA_MEDIA_MEDIA_PLAYER_H_

#include "media/AudioDecoder.h"
#include "media/MediaPacer.h"
#include "media/MediaPlayerObserver.h"
#include "media/MediaReader.h"
#include "media/VideoDecoder.h"
#include "webrtc_java_api.h"

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

namespace ffmpeg
{
	// Kept in step with the Java MediaPlayerState enum, which is what these
	// values are reported to the observer as.
	enum MediaPlayerState
	{
		kIdle = 0,
		kPlaying = 1,
		kPaused = 2,
		kEnded = 3,
		kClosed = 4
	};

	// Plays a media source into webrtc-java's custom media sources.
	//
	// One thread reads packets, decodes them and hands the result to a
	// MediaPacer, which delivers it in real time. That thread is the only one
	// that touches the reader or the decoders, so the commands below do not
	// act directly: they leave a request behind and wake it.
	//
	// Decoding runs ahead of playback only as far as the pacer's queues allow.
	// Once those are full the decode thread blocks, which is what keeps a
	// whole file from being decoded into memory at once.
	class MediaPlayer
	{
		public:
			// The sources may be 0, in which case media of that kind is
			// decoded and dropped. The observer may be null.
			MediaPlayer(std::unique_ptr<MediaReader> reader,
					const webrtc_java_api * api, void * video_source,
					void * audio_source);
			~MediaPlayer();

			MediaPlayer(const MediaPlayer &) = delete;
			MediaPlayer & operator=(const MediaPlayer &) = delete;

			// Takes over the observer, which then lives as long as the
			// player does. Call this before Initialize: the observer is read
			// without the lock while it is being called, on the assumption
			// that it does not change once the thread is running.
			void SetObserver(std::unique_ptr<MediaPlayerObserver> observer);

			// Opens the decoders for whichever streams the source has.
			// Returns 0 or a negative AVERROR.
			int Initialize();

			void Play();
			void Pause();

			// Moves playback to the given position, in microseconds from the
			// start. What is already queued is dropped.
			void Seek(int64_t position_us);

			void SetLooping(bool looping);
			bool IsLooping() const;

			// Stops playback and releases the thread. The player cannot be
			// used afterwards.
			void Close();

			// Where playback has got to within the current pass, in
			// microseconds. A looping player starts again from zero.
			int64_t GetPositionUs() const;

			int GetState() const;

		private:
			void Run();

			// Reads and decodes until the source is exhausted. Returns false
			// if playback should stop, either on error or on close.
			bool PumpOnce(AVPacket * packet, bool * end_of_stream);

			// Pushes everything the decoders still hold, which is what the end
			// of a stream and a loop both need.
			void DrainDecoders();

			int DecodeVideo();
			int DecodeAudio();

			void PerformSeek(int64_t position_us);
			void SetState(int state);

			// Records the state, with the lock held. Returns whether it
			// changed, in which case the caller reports it with NotifyState
			// once the lock is released.
			bool UpdateStateLocked(int state);
			void NotifyState(int state);

			// Stops playback where it is, as a pause does, and reports the
			// error. The thread stays, so that playback can be resumed.
			void ReportError(const std::string & message, int error);

			std::unique_ptr<MediaReader> reader_;
			std::unique_ptr<MediaPacer> pacer_;
			VideoDecoder video_decoder_;
			AudioDecoder audio_decoder_;

			const webrtc_java_api * api_;
			bool has_video_ = false;
			bool has_audio_ = false;

			std::thread thread_;

			mutable std::mutex mutex_;
			std::condition_variable command_;

			std::unique_ptr<MediaPlayerObserver> observer_;
			bool running_ = false;
			bool playing_ = false;
			bool closing_ = false;
			int state_ = kIdle;

			bool seek_pending_ = false;
			int64_t seek_position_us_ = 0;

			// Set once the source has run out and everything queued has been
			// delivered, so that the thread stops pumping until something
			// asks it to start again.
			bool ended_ = false;

			// The largest presentation time seen, used to advance the loop
			// offset when the container does not say how long it runs.
			int64_t max_source_pts_us_ = 0;

			std::atomic<bool> looping_{ false };

			// Added to every presentation time handed to the pacer, so that a
			// source played again keeps producing times that increase. The
			// pacer needs that: WebRTC drops a frame whose capture time does
			// not advance.
			std::atomic<int64_t> loop_offset_us_{ 0 };
	};
}

#endif
