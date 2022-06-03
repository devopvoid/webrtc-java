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

#include "media/audio/AudioTransportSink.h"
#include "JavaClasses.h"
#include "JNI_WebRTC.h"

namespace jni
{
	AudioTransportSink::AudioTransportSink(JNIEnv * env, const JavaGlobalRef<jobject> & sink) :
		sink(sink),
		javaClass(JavaClasses::get<JavaAudioSinkClass>(env))
	{
	}

	int32_t AudioTransportSink::RecordedDataIsAvailable(const void * audioSamples,
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
		JNIEnv * env = AttachCurrentThread();

		const jbyte * buffer = static_cast<const jbyte *>(audioSamples);
		jsize dataSize = static_cast<jsize>(nSamples * nBytesPerSample);

		jbyteArray dataArray = env->NewByteArray(dataSize);
		env->SetByteArrayRegion(dataArray, 0, dataSize, buffer);
		env->CallVoidMethod(sink, javaClass->onRecordedData, dataArray, nSamples, nBytesPerSample, nChannels, samplesPerSec, totalDelayMS, clockDrift);
		env->DeleteLocalRef(dataArray);

		return 0;
	}

	AudioTransportSink::JavaAudioSinkClass::JavaAudioSinkClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG_AUDIO"AudioSink");

		onRecordedData = GetMethod(env, cls, "onRecordedData", "([BIIIIII)V");
	}
}