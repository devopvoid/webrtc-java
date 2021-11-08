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

#include "media/audio/AudioProcessingStreamConfig.h"

#include "JavaClasses.h"
#include "JavaObject.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	namespace AudioProcessingStreamConfig
	{
		webrtc::StreamConfig toNative(JNIEnv * env, const JavaRef<jobject> & javaType)
		{
			const auto javaClass = JavaClasses::get<JavaAudioProcessingStreamConfigClass>(env);

			JavaObject obj(env, javaType);

			return webrtc::StreamConfig(obj.getInt(javaClass->sampleRate), obj.getInt(javaClass->channels));
		}

		JavaAudioProcessingStreamConfigClass::JavaAudioProcessingStreamConfigClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG_AUDIO"AudioProcessingStreamConfig");

			ctor = GetMethod(env, cls, "<init>", "(II)V");

			sampleRate = GetFieldID(env, cls, "sampleRate", "I");
			channels = GetFieldID(env, cls, "channels", "I");
		}
	}
}