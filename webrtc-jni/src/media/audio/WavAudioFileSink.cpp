/*
 * Copyright 2019 Alex Andres
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

#include "media/audio/WavAudioFileSink.h"

namespace jni
{
	WavAudioFileSink::WavAudioFileSink(std::string fileName)
	{
		wavWriter = std::make_unique<webrtc::WavWriter>(fileName, 48000, 2);
	}

	WavAudioFileSink::~WavAudioFileSink()
	{
	}

	int32_t WavAudioFileSink::RecordedDataIsAvailable(
		const void * audioSamples,
		const size_t nSamples,
		const size_t nBytesPerSample,
		const size_t nChannels,
		const uint32_t samplesPerSec,
		const uint32_t totalDelayMS,
		const int32_t clockDrift,
		const uint32_t currentMicLevel,
		const bool keyPressed,
		uint32_t & newMicLevel)
	{
		wavWriter->WriteSamples(static_cast<const int16_t *>(audioSamples), nSamples * nChannels);

		return 0;
	}
}