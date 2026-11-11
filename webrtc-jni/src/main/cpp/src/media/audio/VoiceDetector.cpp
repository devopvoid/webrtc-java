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

#include "media/audio/VoiceDetector.h"

#include "api/audio/audio_view.h"

namespace jni
{
	namespace
	{
		// The detector works on 10 ms at 16 kHz.
		constexpr int kDetectorRate = 16000;
		constexpr size_t kDetectorFrames = kDetectorRate / 100;

		// What PushResampler takes in 10 ms, which is 384 kHz.
		constexpr size_t kMaxFrames = 3840;

		// The chunks the detector collects before its first probability.
		constexpr int kWarmUpChunks = 3;
	}

	VoiceDetector::VoiceDetector() :
		detector(std::make_unique<webrtc::VoiceActivityDetector>()),
		chunks(0)
	{
	}

	std::optional<float> VoiceDetector::Process(const int16_t * data, int sampleRate, size_t channels, size_t frames)
	{
		if (channels == 1) {
			return ProcessMono(data, sampleRate, frames);
		}
		if (channels == 0 || frames > kMaxFrames) {
			return std::nullopt;
		}

		mono.resize(frames);

		for (size_t frame = 0; frame < frames; frame++) {
			int sum = 0;

			for (size_t channel = 0; channel < channels; channel++) {
				sum += data[frame * channels + channel];
			}

			mono[frame] = static_cast<int16_t>(sum / static_cast<int>(channels));
		}

		return ProcessMono(mono.data(), sampleRate, frames);
	}

	std::optional<float> VoiceDetector::ProcessMono(const int16_t * data, int sampleRate, size_t frames)
	{
		if (frames == 0 || frames > kMaxFrames || static_cast<size_t>(sampleRate) != frames * 100) {
			return std::nullopt;
		}

		const int16_t * chunk = data;

		if (frames != kDetectorFrames) {
			resampler.Resample(webrtc::MonoView<const int16_t>(data, frames),
				webrtc::MonoView<int16_t>(resampled.data(), resampled.size()));

			chunk = resampled.data();
		}

		detector->ProcessChunk(chunk, kDetectorFrames, kDetectorRate);

		if (chunks < kWarmUpChunks && ++chunks < kWarmUpChunks) {
			// Until then the detector reports its initial 1.
			return std::nullopt;
		}

		return detector->last_voice_probability();
	}

	void VoiceDetector::Reset()
	{
		detector = std::make_unique<webrtc::VoiceActivityDetector>();
		chunks = 0;
	}
}
