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
#include <functional>
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
	// thread is jostled. The mapping starts at the position playback starts
	// from, if audio gets here first, as video that belongs before it may
	// still be in the decoder.
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

			// What the pictures in the video queue may take, on top of their
			// number. Sixty pictures in 4K would take over 700 MiB, for each
			// player. This leaves 1080p its sixty pictures, of about 3 MiB
			// each, and 4K about twenty, which is still most of a second.
			// A single picture larger than this goes through on its own.
			static constexpr size_t kVideoByteCapacity = 256 * 1024 * 1024;

			// How far audio may run past its capacity while video is short.
			//
			// One thread decodes both, in the order the container stores
			// them, so a full audio queue holds up video as well. Containers
			// interleave coarsely, and a frame-threaded video decoder hands
			// out each frame several packets after it was fed, so video can
			// lag audio in the file by a second or more. Blocking on audio
			// then leaves the video queue running dry, and its frames arrive
			// late and all at once, which WebRTC's encoder answers by dropping
			// all but the last of each burst. Letting audio grow while video
			// is below half its capacity, in pictures and in bytes, is what
			// keeps video fed; this bounds how much, at ten seconds, for a
			// source that has no video at all.
			static constexpr size_t kAudioOverflowCapacity = 1000;

			// How far past the position playback starts from the first audio
			// may lie for the mapping to start at that position regardless.
			// It covers what a decoder holds back, which is up to one picture
			// for each of its threads. Further than that the video would be
			// here by now, and a source with timestamps that do not count
			// from its start must not be held up for as long as they say.
			static constexpr int64_t kMaxStartGapUs = 5000000;

			// The sources may be 0, in which case media of that kind is
			// dropped rather than delivered.
			MediaPacer(const webrtc_java_api * api, void * video_source,
					void * audio_source);
			~MediaPacer();

			MediaPacer(const MediaPacer &) = delete;
			MediaPacer & operator=(const MediaPacer &) = delete;

			// Set before Start; called without the pacer lock.
			void SetErrorHandler(std::function<void(const char *, int, bool)> handler);
			void Start();
			// Stops accepting and delivering items, without joining this thread.
			void Cancel();

			// Stops the thread and drops whatever is still queued. Safe to
			// call more than once.
			void Stop();

			// Hands over a decoded frame and its presentation time, which is
			// its position in the source. A source played again is pushed
			// with a loop offset, which goes on top of that to make the times
			// of all its passes one timeline that only rises. Ownership of
			// the frame passes to the pacer whether this succeeds or not.
			//
			// Blocks while the queue is full, which is the backpressure that
			// keeps decoding from running ahead of playback. Returns false
			// once the pacer has been stopped, or once the generation given
			// is no longer the current one: what was decoded for it belongs
			// to a position that is being left, so it is dropped, and a wait
			// for room ends there, as nothing else may ever end it.
			bool PushVideo(AVFrame * frame, int64_t timestamp_us, int64_t loop_offset_us,
					uint64_t generation);

			// Hands over one 10 ms chunk of interleaved 16-bit PCM.
			bool PushAudio(std::vector<int16_t> && samples, int channels,
					int64_t timestamp_us, int64_t loop_offset_us, uint64_t generation);

			void Pause();
			void Resume();

			// Drops everything queued and forgets the clock mapping, so that
			// the next item delivered starts a new one. This is what a seek
			// needs: what is queued belongs to the position being left. The
			// position is where playback is taken to be until an item has been
			// delivered, in the source: the first item then sets it, wherever
			// that landed, and starts a new timeline with no loop offset.
			//
			// Exact says that playback really starts at this position, which
			// a seek to a keyframe does not: there, what is played comes as
			// it lands, so the first item starts the mapping.
			void Flush(int64_t position_us, bool exact);

			// Flushes, and starts a new generation, which turns away what is
			// still being pushed for the one before. This is for a seek that
			// has been asked for, by a thread other than the one pushing,
			// which may have more on its way than the queue can take. With
			// playback paused nothing would ever make room for that, and the
			// thread would never get round to the seek.
			void StartGeneration(int64_t position_us);

			// The generation pushes have to name to be taken. The thread
			// pushing reads this when it starts on a seek, so that everything
			// it pushes from there on was decoded for that seek.
			uint64_t GetGeneration() const;

			// True once everything handed over has been delivered: the
			// queues are empty and no item is being handed to a source.
			bool IsDrained() const;

			// Where playback has got to in the source, in the pass that is
			// being delivered: the furthest item delivered so far. It only
			// moves forward, as video and audio are delivered independently
			// and a frame that arrives late is behind what has been heard,
			// other than when a flush moves it, or a pass of the source ends
			// and the next one starts. That happens as the next pass reaches
			// delivery, however far ahead the decoder has started it.
			int64_t GetPositionUs() const;

			// What the pictures in the video queue take right now, in bytes.
			size_t GetQueuedVideoBytes() const;

			// The most they have taken so far. Nothing delivered counts, nor
			// the pictures the decoder holds on to.
			size_t GetPeakQueuedVideoBytes() const;

			// True on the pacing thread, which is the one that calls the sinks
			// of the tracks fed by the sources.
			bool IsOwnThread() const;

		private:
			struct VideoItem
			{
				AVFrame * frame = nullptr;
				int64_t timestamp_us = 0;
				int64_t loop_offset_us = 0;
				size_t bytes = 0;

				VideoItem() = default;
				VideoItem(AVFrame * f, int64_t ts, int64_t offset, size_t size)
					: frame(f), timestamp_us(ts), loop_offset_us(offset), bytes(size) {}
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
				int64_t loop_offset_us = 0;
			};

			void Run();

			void FlushLocked(int64_t position_us, bool exact);

			// Takes in the time of an item about to be delivered, on the
			// timeline of all passes, and the loop offset of its pass.
			void AdvancePosition(int64_t timestamp_us, int64_t loop_offset_us);

			// Whether a picture of the given size fits the video queue. An
			// empty queue takes anything, as nothing else would ever let a
			// picture larger than the queue through.
			bool HasVideoRoom(size_t bytes) const;

			// Whether video is below half of what its queue takes.
			bool IsVideoShort() const;

			// Both run without the lock held: the extension API delivers
			// synchronously into WebRTC, which must not happen underneath a
			// lock of ours. The time on the WebRTC clock is worked out by the
			// caller while it still holds the lock and passed in, so that
			// neither of these reads the mapping unguarded.
			int DeliverVideo(VideoItem item, int64_t mapped_us);
			int DeliverAudio(const AudioItem & item, int64_t mapped_us);

			struct OutputStatus
			{
				int consecutive_failures = 0;
				bool reported = false;
				int64_t last_report_us = 0;
			};
			void CheckOutput(OutputStatus & status, const char * media, int result);
			OutputStatus video_output_;
			OutputStatus audio_output_;
			std::function<void(const char *, int, bool)> error_handler_;

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

			// What the pictures in the video queue take, and the most they
			// have taken.
			size_t video_bytes_ = 0;
			size_t peak_video_bytes_ = 0;

			bool running_ = false;
			bool paused_ = false;

			// Items taken from a queue whose delivery has not returned yet.
			int delivering_ = 0;

			// Counts the seeks asked for.
			uint64_t generation_ = 0;

			// Presentation time, on the timeline of all passes, plus this is
			// a time on the WebRTC clock.
			int64_t base_us_ = 0;
			bool have_base_ = false;

			// Whether the mapping to come starts at the position.
			bool exact_start_ = true;
			int64_t paused_at_us_ = 0;

			// Where playback has got to on the timeline of all passes, the
			// loop offset of the pass that is in, and whether an item has set
			// them since the last flush.
			int64_t position_us_ = 0;
			int64_t position_offset_us_ = 0;
			bool position_set_ = false;
	};
}

#endif
