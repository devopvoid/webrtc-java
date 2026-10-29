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

	void AudioCaptureTap::SetSink(std::unique_ptr<AudioTrackSink> next)
	{
		std::unique_ptr<AudioTrackSink> replaced;

		{
			// Waits for a delivery in progress, which holds the lock.
			std::lock_guard<std::mutex> lock(mutex);

			replaced = std::move(sink);
			sink = std::move(next);
		}

		// Deleted outside the lock: it releases a Java reference.
		replaced.reset();
	}

	void AudioCaptureTap::Process(const webrtc::AudioBuffer & audio)
	{
		std::lock_guard<std::mutex> lock(mutex);

		if (!sink) {
			return;
		}

		const size_t channels = audio.num_channels();
		const size_t frames = audio.num_frames();
		const float * const * data = audio.channels_const();

		samples.resize(channels * frames);

		// The buffer holds one channel after another, as floats in the range
		// of 16-bit samples; a sink takes them interleaved.
		for (size_t channel = 0; channel < channels; channel++) {
			for (size_t frame = 0; frame < frames; frame++) {
				samples[frame * channels + channel] = webrtc::FloatS16ToS16(data[channel][frame]);
			}
		}

		// AudioProcessing works on 10 ms at a time.
		sink->OnData(samples.data(), 16, static_cast<int>(frames * 100), channels, frames);
	}
}
