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

			// Asks for video to be decoded in hardware where the platform can.
			// Call this before Initialize.
			void SetHardwareDecoding(bool hardware);

			// Whether video is decoded in hardware right now, which can be
			// less than was asked for.
			bool IsHardwareDecoding() const;

			// Opens the decoders for whichever streams the source has.
			// Returns 0 or a negative AVERROR.
			int Initialize();

			void Play();
			void Pause();

			// Moves playback to the given position, in microseconds from the
			// start. What is already queued is dropped. A precise seek drops
			// what is decoded between the keyframe it lands on and the
			// position as well; one that is not plays from that keyframe.
			void Seek(int64_t position_us, bool precise);

			void SetLooping(bool looping);
			bool IsLooping() const;

			// Stops playback and releases the thread. The player cannot be
			// used afterwards.
			void Close();

			// Where playback has got to within the current pass, in
			// microseconds. A looping player starts again from zero.
			int64_t GetPositionUs() const;

			int GetState() const;

			// True on any thread of the player, which are the ones its
			// observer and the sinks of the tracks it feeds are called on.
			// Such a thread cannot destroy the player: that waits for the
			// thread, and what is below the call on its stack still uses
			// the player.
			bool IsOwnThread() const;

		private:
			void Run();

			// Reads and decodes until the source is exhausted. Returns false
			// if playback should stop, either on error or on close.
			bool PumpOnce(AVPacket * packet, bool * end_of_stream);

			// Pushes everything the decoders still hold, which is what the end
			// of a stream and a loop both need.
			void DrainDecoders();

			// Takes the decoded video and hands it to the pacer. At the end
			// of the stream a seek that landed too late is not retried: the
			// source is about to end or start over, and that would be lost.
			int DecodeVideo(bool at_end);
			int DecodeAudio();

			void PerformSeek(int64_t position_us, bool precise);

			// Moves the reader to the given time and starts decoding from
			// there towards the position of the seek being carried out.
			// Returns 0 or a negative AVERROR.
			int SeekReader(int64_t from_us);

			// Seeks again further back, after the video decoded since a seek
			// started past its position: the reader landed past the keyframe
			// before it. Only a precise seek does: a fast one plays from the
			// keyframe it lands on. Returns whether it did; there is nothing
			// further back to try once the reader would land where it did
			// already, or the distance has grown past kMaxSeekBackUs.
			bool SeekFurtherBack();
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
			bool hardware_decoding_ = false;
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
			bool seek_precise_ = true;

			// How far before the position a seek that landed too late looks
			// next, doubling each time, and how far at most: past that, a
			// stream whose keyframes are this far apart starts at the next
			// one, as a reader without an index would leave it.
			static constexpr int64_t kFirstSeekBackUs = 1000000;
			static constexpr int64_t kMaxSeekBackUs = 64000000;

			// The seek being carried out, on the decode thread: its position,
			// whether it is precise, how far before it the reader was last
			// sent, and where to.
			int64_t seek_target_us_ = 0;
			bool seek_target_precise_ = true;
			int64_t seek_back_us_ = 0;
			int64_t seek_from_us_ = 0;

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
