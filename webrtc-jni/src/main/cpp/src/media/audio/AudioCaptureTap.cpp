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

#include "media/audio/AudioCaptureTap.h"

#include "common_audio/include/audio_util.h"
#include "modules/audio_processing/audio_buffer.h"

#include <string>

namespace jni
{
	namespace
	{
		class CaptureTapProcessing : public webrtc::CustomProcessing
		{
			public:
				explicit CaptureTapProcessing(std::shared_ptr<AudioCaptureTap> tap) :
					tap(std::move(tap))
				{
				}

				void Initialize(int sampleRateHz, int numChannels) override
				{
					// The tap reads the format from each buffer.
				}

				void Process(webrtc::AudioBuffer * audio) override
				{
					tap->Process(*audio);
				}

				std::string ToString() const override
				{
					return "AudioCaptureTap";
				}

			private:
				const std::shared_ptr<AudioCaptureTap> tap;
		};
	}

	std::unique_ptr<webrtc::CustomProcessing> AudioCaptureTap::CreateProcessing(std::shared_ptr<AudioCaptureTap> tap)
	{
		return std::make_unique<CaptureTapProcessing>(std::move(tap));
	}

	VoiceActivityListener * AudioCaptureTap::AddListener(std::unique_ptr<VoiceActivityListener> listener)
	{
		std::lock_guard<std::mutex> lock(mutex);

		if (listeners.empty()) {
			// The audio it saw last may be long ago.
			detector.Reset();
		}

		listeners.push_back(std::move(listener));

		return listeners.back().get();
	}

	void AudioCaptureTap::RemoveListener(VoiceActivityListener * listener)
	{
		std::unique_ptr<VoiceActivityListener> removed;

		{
			// Waits for a delivery in progress, which holds the lock.
			std::lock_guard<std::mutex> lock(mutex);

			for (auto it = listeners.begin(); it != listeners.end(); ++it) {
				if (it->get() == listener) {
					removed = std::move(*it);
					listeners.erase(it);
					break;
				}
			}
		}

		// Deleted outside the lock: it releases a Java reference.
		removed.reset();
	}

	void AudioCaptureTap::Process(const webrtc::AudioBuffer & audio)
	{
		std::lock_guard<std::mutex> lock(mutex);

		if (listeners.empty()) {
			return;
		}

		const size_t channels = audio.num_channels();
		const size_t frames = audio.num_frames();
		const float * const * data = audio.channels_const();

		samples.resize(frames);

		// The buffer holds one channel after another, as floats in the range
		// of 16-bit samples; the detector takes them mixed down to mono.
		for (size_t frame = 0; frame < frames; frame++) {
			float sum = 0;

			for (size_t channel = 0; channel < channels; channel++) {
				sum += data[channel][frame];
			}

			samples[frame] = webrtc::FloatS16ToS16(sum / static_cast<float>(channels));
		}

		// AudioProcessing works on 10 ms at a time.
		std::optional<float> probability = detector.ProcessMono(samples.data(), static_cast<int>(frames * 100), frames);

		if (!probability) {
			return;
		}

		for (const auto & listener : listeners) {
			listener->Update(*probability);
		}
	}
}
