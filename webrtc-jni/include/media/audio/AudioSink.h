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

#ifndef JNI_WEBRTC_MEDIA_AUDIO_SINK_H_
#define JNI_WEBRTC_MEDIA_AUDIO_SINK_H_

#include "modules/audio_device/include/audio_device_defines.h"

namespace jni
{
	class AudioSink : public webrtc::AudioTransport
	{
		public:
			virtual ~AudioSink() {};

			int32_t NeedMorePlayData(const size_t nSamples,
				const size_t nBytesPerSample,
				const size_t nChannels,
				const uint32_t samplesPerSec,
				void* audioSamples,
				size_t& nSamplesOut,
				int64_t* elapsed_time_ms,
				int64_t* ntp_time_ms)
			{
				*elapsed_time_ms = 0;
				*ntp_time_ms = 0;

				return 0;
			}

			void PullRenderData(int bits_per_sample,
				int sample_rate,
				size_t number_of_channels,
				size_t number_of_frames,
				void* audio_data,
				int64_t* elapsed_time_ms,
				int64_t* ntp_time_ms)
			{
			}
	};
}

#endif