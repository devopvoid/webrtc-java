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

#ifndef JNI_WEBRTC_MEDIA_RAW_AUDIO_FILE_SINK_H_
#define JNI_WEBRTC_MEDIA_RAW_AUDIO_FILE_SINK_H_

#include "AudioSink.h"

#include <fstream>
#include <memory>
#include <string>

namespace jni
{
	class RawAudioFileSink : public AudioSink
	{
		public:
			RawAudioFileSink(std::string fileName);
			~RawAudioFileSink();

		int32_t RecordedDataIsAvailable(
			const void * audioSamples,
			const size_t nSamples,
			const size_t nBytesPerSample,
			const size_t nChannels,
			const uint32_t samplesPerSec,
			const uint32_t totalDelayMS,
			const int32_t clockDrift,
			const uint32_t currentMicLevel,
			const bool keyPressed,
			uint32_t & newMicLevel) override;

		private:
			std::ofstream outputStream;
	};
}

#endif