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

#include "media/audio/RawAudioFileSource.h"

#include <algorithm>
#include <cstring>

namespace jni
{
	RawAudioFileSource::RawAudioFileSource(std::string fileName)
	{
		inputStream.open(fileName, std::ofstream::in | std::ofstream::binary);

		// Get length of the file.
		inputStream.seekg(0, inputStream.end);
		fileLength = inputStream.tellg();
		fileRemainingBytes = fileLength;
		inputStream.seekg(0, inputStream.beg);
	}

	RawAudioFileSource::~RawAudioFileSource()
	{
		inputStream.close();
	}

	int32_t RawAudioFileSource::NeedMorePlayData(
		const size_t nSamples,
		const size_t nBytesPerSample,
		const size_t nChannels,
		const uint32_t samplesPerSec,
		void * audioSamples,
		size_t & nSamplesOut,
		int64_t * elapsed_time_ms,
		int64_t * ntp_time_ms)
	{
		size_t audioSamplesSize = nSamples * nBytesPerSample;
		size_t readBytes = std::min(audioSamplesSize, fileRemainingBytes);

		*elapsed_time_ms = 0;
		*ntp_time_ms = 0;

		if (readBytes < nBytesPerSample) {
			// EOF. Fill with silence.
			nSamplesOut = nSamples;

			std::memset(audioSamples, 0, audioSamplesSize);

			return 0;
		}

		nSamplesOut = readBytes / nBytesPerSample;
		fileRemainingBytes -= readBytes;

		inputStream.read(static_cast<char *>(audioSamples), readBytes);

		return 0;
	}
}