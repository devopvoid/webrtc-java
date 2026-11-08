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

#ifndef JNI_WEBRTC_MEDIA_AUDIO_SYSTEM_AUDIO_CAPTURE_H_
#define JNI_WEBRTC_MEDIA_AUDIO_SYSTEM_AUDIO_CAPTURE_H_

#include "media/audio/CustomAudioSource.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <string>

namespace jni
{
	// Captures what the system plays on its default output, as 16-bit 48 kHz
	// stereo PCM. Each platform implements Open() and Poll().
	//
	// Platform callbacks only write PCM into a bounded queue; they never call
	// Java or WebRTC. The owning Java worker calls Pump(), which polls the
	// platform and delivers the complete 10 ms chunks to a CustomAudioSource.
	// Open(), Pump() and the destructor run on that one worker thread.
	class SystemAudioCapture
	{
		public:
			static constexpr int kSampleRate = 48000;
			static constexpr size_t kChannels = 2;
			static constexpr size_t kFramesPerChunk = kSampleRate / 100;

			// Creates the capture of the current platform, not opened yet.
			static std::unique_ptr<SystemAudioCapture> Create();

			virtual ~SystemAudioCapture() = default;

			// Opens the default output. Throws an Exception on failure.
			virtual void Open() = 0;

			// Appends the audio the platform has buffered since the last call.
			// Throws an Exception once capture has failed.
			virtual void Poll() = 0;

			// Polls the platform and pushes every complete chunk to the source,
			// stamped with its capture time.
			void Pump(CustomAudioSource * source);

			// Queues interleaved frames whose first one was captured at the given
			// time, on the clock of webrtc::TimeMicros(). Null samples queue
			// silence of the given length.
			void Append(const int16_t * samples, size_t frames, int64_t timestamp_us);

			// Records a failure, which the next Pump() throws. Safe to call from
			// platform callbacks.
			void Fail(const char * message) noexcept;

		private:
			struct Chunk
			{
				std::array<int16_t, kFramesPerChunk * kChannels> samples{};
				size_t frames = 0;
				int64_t timestamp_us = 0;
			};

			// Guards chunks_ and failure_.
			std::mutex mutex_;
			std::deque<Chunk> chunks_;
			std::string failure_;
	};
}

#endif
