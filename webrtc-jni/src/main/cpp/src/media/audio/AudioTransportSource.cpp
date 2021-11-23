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

#include "media/audio/AudioTransportSource.h"
#include "JavaClasses.h"
#include "JNI_WebRTC.h"

#include <algorithm>

namespace jni
{
	AudioTransportSource::AudioTransportSource(JNIEnv * env, const JavaGlobalRef<jobject> & source) :
		source(source),
		javaClass(JavaClasses::get<JavaAudioSourceClass>(env))
	{
	}

	int32_t AudioTransportSource::NeedMorePlayData(const size_t nSamples,
                                                   const size_t nBytesPerSample,
                                                   const size_t nChannels,
                                                   const uint32_t samplesPerSec,
                                                   void * audioSamples,
                                                   size_t & nSamplesOut,
                                                   int64_t * elapsed_time_ms,
                                                   int64_t * ntp_time_ms)
	{
		JNIEnv * env = AttachCurrentThread();

		jsize bufferSize = nSamples * nBytesPerSample;
		jbyteArray dataArray = env->NewByteArray(bufferSize);
		jsize bufferLength = env->GetArrayLength(dataArray);

		*elapsed_time_ms = 0;
		*ntp_time_ms = 0;

		if (bufferLength < bufferSize) {
			env->DeleteLocalRef(dataArray);
			return 0;
		}

		nSamplesOut = env->CallIntMethod(source, javaClass->onPlaybackData, dataArray, nSamples, nBytesPerSample, nChannels, samplesPerSec);

		if (nSamplesOut > 0) {
			jbyte * buf = env->GetByteArrayElements(dataArray, NULL);

			std::memcpy(audioSamples, buf, bufferSize);

			env->ReleaseByteArrayElements(dataArray, buf, JNI_ABORT);
		}
		else if (nSamplesOut < nSamples) {
			// EOF. Fill with silence.
			std::memset(audioSamples, 0, bufferSize);
		}

		return 0;
	}

	AudioTransportSource::JavaAudioSourceClass::JavaAudioSourceClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG_AUDIO"AudioSource");

		onPlaybackData = GetMethod(env, cls, "onPlaybackData", "([BIIII)I");
	}
}