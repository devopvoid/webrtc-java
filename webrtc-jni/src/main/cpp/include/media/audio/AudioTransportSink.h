/*
 * Copyright 2021 Alex Andres
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

#ifndef JNI_WEBRTC_API_AUDIO_TRANSPORT_SINK_H_
#define JNI_WEBRTC_API_AUDIO_TRANSPORT_SINK_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include "AudioSink.h"

#include <jni.h>

namespace jni
{
	class AudioTransportSink : public AudioSink
	{
		public:
			AudioTransportSink(JNIEnv * env, const JavaGlobalRef<jobject> & sink);
			~AudioTransportSink() = default;

			// AudioTransport implementation.
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
			class JavaAudioSinkClass : public JavaClass
			{
				public:
					explicit JavaAudioSinkClass(JNIEnv * env);

					jmethodID onRecordedData;
			};

		private:
			JavaGlobalRef<jobject> sink;

			const std::shared_ptr<JavaAudioSinkClass> javaClass;
	};
}

#endif