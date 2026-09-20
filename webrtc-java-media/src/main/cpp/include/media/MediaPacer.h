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

#ifndef WEBRTC_JAVA_MEDIA_MEDIA_PACER_H_
#define WEBRTC_JAVA_MEDIA_MEDIA_PACER_H_

#include "webrtc_java_api.h"

#include <condition_variable>
#include <cstdint>
#include <deque>
#include <mutex>
#include <thread>
#include <vector>

extern "C" {
#include <libavutil/frame.h>
}

namespace ffmpeg
{
	// Delivers decoded media to webrtc-java's custom sources in real time.
	//
	// WebRTC encodes and sends a frame the moment it arrives, so playback has
	// to be paced rather than pushed as fast as it decodes. One thread does
	// that: it sleeps until an item is due and then hands it over, which is
	// the only thread that ever touches the extension API.
	//
	// Timing works off one mapping, made when the first item is delivered and
	// kept from then on: a source's own presentation time plus a base offset
	// is a time on the WebRTC clock. Every item carries that time with it, so
	// the timeline that reaches the receiver is the source's own and not the
	// pacing thread's, and audio stays lined up with video however much the
	// thread is jostled.
	//
	// Video and audio are paced independently. They go to different sources
	// and need no ordering between them, and keeping them independent is also
	// what keeps a full video queue from starving audio.
	class MediaPacer
	{
		public:
			// The queues are what decouples decoding from playback. A second
			// of audio and about a second of video is enough to ride out a
			// slow decode without holding much memory.
			static constexpr size_t kVideoCapacity = 60;
			static constexpr size_t kAudioCapacity = 100;

			// The sources may be 0, in which case media of that kind is
			// dropped rather than delivered.
			MediaPacer(const webrtc_java_api * api, void * video_source,
					void * audio_source);
			~MediaPacer();

			MediaPacer(const MediaPacer &) = delete;
			MediaPacer & operator=(const MediaPacer &) = delete;

			void Start();

			// Stops the thread and drops whatever is still queued. Safe to
			// call more than once.
			void Stop();

			// Hands over a decoded frame and its presentation time. Ownership
			// of the frame passes to the pacer whether this succeeds or not.
			//
			// Blocks while the queue is full, which is the backpressure that
			// keeps decoding from running ahead of playback. Returns false
			// once the pacer has been stopped.
			bool PushVideo(AVFrame * frame, int64_t timestamp_us);

			// Hands over one 10 ms chunk of interleaved 16-bit PCM.
			bool PushAudio(std::vector<int16_t> && samples, int channels,
					int64_t timestamp_us);

			void Pause();
			void Resume();

			// Drops everything queued and forgets the clock mapping, so that
			// the next item delivered starts a new one. This is what a seek
			// needs: what is queued belongs to the position being left.
			void Flush();

			// True once everything handed over has been delivered.
			bool IsDrained() const;

			// The presentation time of the item delivered last, which is where
			// playback has got to.
			int64_t GetPositionUs() const;

		private:
			struct VideoItem
			{
				AVFrame * frame = nullptr;
				int64_t timestamp_us = 0;

				VideoItem() = default;
				VideoItem(AVFrame * f, int64_t ts) : frame(f), timestamp_us(ts) {}
				~VideoItem();

				VideoItem(VideoItem && other) noexcept;
				VideoItem & operator=(VideoItem && other) noexcept;

				VideoItem(const VideoItem &) = delete;
				VideoItem & operator=(const VideoItem &) = delete;
			};

			struct AudioItem
			{
				std::vector<int16_t> samples;
				int channels = 0;
				int64_t timestamp_us = 0;
			};

			void Run();

			// Both run without the lock held: the extension API delivers
			// synchronously into WebRTC, which must not happen underneath a
			// lock of ours. The time on the WebRTC clock is worked out by the
			// caller while it still holds the lock and passed in, so that
			// neither of these reads the mapping unguarded.
			void DeliverVideo(VideoItem item, int64_t mapped_us);
			void DeliverAudio(const AudioItem & item, int64_t mapped_us);

			const webrtc_java_api * api_;
			void * video_source_;
			void * audio_source_;

			std::thread thread_;

			mutable std::mutex mutex_;
			std::condition_variable work_;
			std::condition_variable video_space_;
			std::condition_variable audio_space_;

			std::deque<VideoItem> video_queue_;
			std::deque<AudioItem> audio_queue_;

			bool running_ = false;
			bool paused_ = false;

			// Presentation time plus this is a time on the WebRTC clock.
			int64_t base_us_ = 0;
			bool have_base_ = false;
			int64_t paused_at_us_ = 0;
			int64_t position_us_ = 0;
	};
}

#endif
