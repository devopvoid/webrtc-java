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

#include "media/audio/RawAudioFileSink.h"

#include <iostream>

namespace jni
{
	RawAudioFileSink::RawAudioFileSink(std::string fileName)
	{
		outputStream.open(fileName, std::ofstream::out | std::ofstream::binary | std::ofstream::trunc);
	}

	RawAudioFileSink::~RawAudioFileSink()
	{
		outputStream.flush();
		outputStream.close();
	}

	int32_t RawAudioFileSink::RecordedDataIsAvailable(
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
		size_t audioSamplesSize = nSamples * nBytesPerSample;

		outputStream.write(static_cast<const char *>(audioSamples), audioSamplesSize);

		return 0;
	}
}