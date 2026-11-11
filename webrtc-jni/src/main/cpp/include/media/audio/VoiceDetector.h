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

#ifndef JNI_WEBRTC_MEDIA_AUDIO_VOICE_DETECTOR_H_
#define JNI_WEBRTC_MEDIA_AUDIO_VOICE_DETECTOR_H_

#include "common_audio/resampler/include/push_resampler.h"
#include "modules/audio_processing/vad/voice_activity_detector.h"

#include <array>
#include <cstdint>
#include <memory>
#include <optional>
#include <vector>

namespace jni
{
	// The voice probability of audio in 10 ms chunks, from WebRTC's
	// VoiceActivityDetector. Chunks are mixed down to mono and resampled to
	// the detector's 16 kHz here, at any rate, as the detector's own
	// resampler stops the process on a rate it does not know, such as
	// 44.1 kHz. Not thread-safe.
	class VoiceDetector
	{
		public:
			VoiceDetector();

			// Takes 10 ms of 16-bit PCM with interleaved channels. Returns
			// the voice probability, from 0 to 1, or nothing for a chunk that
			// is not 10 ms long, and for the first 30 ms, which the detector
			// collects before its first probability.
			std::optional<float> Process(const int16_t * data, int sampleRate, size_t channels, size_t frames);

			// Like Process, for mono audio.
			std::optional<float> ProcessMono(const int16_t * data, int sampleRate, size_t frames);

			// Forgets the audio so far, as for a new stream.
			void Reset();

		private:
			std::unique_ptr<webrtc::VoiceActivityDetector> detector;

			webrtc::PushResampler<int16_t> resampler;

			std::vector<int16_t> mono;

			std::array<int16_t, 160> resampled;

			// The chunks processed since the last reset, up to the three the
			// detector needs for a probability.
			int chunks;
	};
}

#endif
