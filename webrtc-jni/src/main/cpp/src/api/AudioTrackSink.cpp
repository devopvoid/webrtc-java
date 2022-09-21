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

#include "api/AudioTrackSink.h"
#include "JavaClasses.h"
#include "JNI_WebRTC.h"

namespace jni
{
	AudioTrackSink::AudioTrackSink(JNIEnv * env, const JavaGlobalRef<jobject> & sink) :
		sink(sink),
		javaClass(JavaClasses::get<JavaAudioTrackSinkClass>(env))
	{
	}

	void AudioTrackSink::OnData(const void * data, int bitsPerSample, int sampleRate, size_t channels, size_t frames)
	{
		JNIEnv * env = AttachCurrentThread();

		const jbyte * buffer = static_cast<const jbyte *>(data);
		jsize dataSize = static_cast<jsize>(frames * channels * (bitsPerSample / 8));

		jbyteArray dataArray = env->NewByteArray(dataSize);
		env->SetByteArrayRegion(dataArray, 0, dataSize, buffer);

		env->CallVoidMethod(sink, javaClass->onData, dataArray, bitsPerSample, sampleRate, channels, frames);
		env->DeleteLocalRef(dataArray);
	}

	AudioTrackSink::JavaAudioTrackSinkClass::JavaAudioTrackSinkClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG_AUDIO"AudioTrackSink");

		onData = GetMethod(env, cls, "onData", "([BIIII)V");
	}
}
