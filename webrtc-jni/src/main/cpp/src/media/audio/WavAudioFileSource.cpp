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

#include "media/audio/WavAudioFileSource.h"

#include "rtc_base/logging.h"

#include <algorithm>
#include <cstring>

namespace jni
{
	WavAudioFileSource::WavAudioFileSource(std::string fileName)
	{
		wavReader = std::make_unique<webrtc::WavReader>(fileName);

		RTC_LOG(LS_INFO) << "WAV file opened:"
			<< " Sample Rate: " << wavReader->sample_rate()
			<< ", Channels: " << wavReader->num_channels()
			<< ", Samples: " << wavReader->num_samples();
	}

	WavAudioFileSource::~WavAudioFileSource()
	{
	}

	int32_t WavAudioFileSource::NeedMorePlayData(
		const size_t nSamples,
		const size_t nBytesPerSample,
		const size_t nChannels,
		const uint32_t samplesPerSec,
		void * audioSamples,
		size_t & nSamplesOut,
		int64_t * elapsed_time_ms,
		int64_t * ntp_time_ms)
	{
		*elapsed_time_ms = 0;
		*ntp_time_ms = 0;

		nSamplesOut = wavReader->ReadSamples(nSamples * nChannels, static_cast<int16_t *>(audioSamples));

		if (nSamplesOut < nSamples) {
			// EOF. Fill with silence.
			nSamplesOut = nSamples * nChannels;

			std::memset(audioSamples, 0, nSamples * nBytesPerSample);
		}

		return 0;
	}
}