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

#include "media/audio/VoiceActivitySink.h"

namespace jni
{
	VoiceActivitySink::VoiceActivitySink(std::unique_ptr<VoiceActivityListener> listener) :
		listener(std::move(listener))
	{
	}

	void VoiceActivitySink::OnData(const void * data, int bitsPerSample, int sampleRate, size_t channels,
		size_t frames)
	{
		if (bitsPerSample != 16) {
			return;
		}

		std::optional<float> probability = detector.Process(static_cast<const int16_t *>(data), sampleRate,
			channels, frames);

		if (probability) {
			listener->Update(*probability);
		}
	}
}
