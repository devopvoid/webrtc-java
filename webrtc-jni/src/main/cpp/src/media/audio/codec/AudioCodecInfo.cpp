/*
 * Copyright 2026 Alex Andres
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

#include "media/audio/codec/AudioCodecInfo.h"
#include "JavaClasses.h"
#include "JavaHashMap.h"
#include "JavaString.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	namespace AudioCodecInfo
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::SdpAudioFormat & format)
		{
			const auto javaClass = JavaClasses::get<JavaAudioCodecInfoClass>(env);

			JavaHashMap parameters(env);

			for (const auto & [key, value] : format.parameters) {
				parameters.put(JavaString::toJava(env, key), JavaString::toJava(env, value));
			}

			JavaLocalRef<jstring> name = JavaString::toJava(env, format.name);
			JavaLocalRef<jobject> parameterMap = parameters;

			jobject info = env->NewObject(javaClass->cls, javaClass->ctor, name.get(),
				static_cast<jint>(format.clockrate_hz), static_cast<jint>(format.num_channels),
				parameterMap.get());

			ExceptionCheck(env);

			return JavaLocalRef<jobject>(env, info);
		}

		JavaLocalRef<jobjectArray> toJavaArray(JNIEnv * env, const std::vector<webrtc::AudioCodecSpec> & specs)
		{
			const auto javaClass = JavaClasses::get<JavaAudioCodecInfoClass>(env);

			JavaLocalRef<jobjectArray> array(env, env->NewObjectArray(static_cast<jsize>(specs.size()), javaClass->cls, nullptr));

			ExceptionCheck(env);

			for (size_t i = 0; i < specs.size(); i++) {
				JavaLocalRef<jobject> info = toJava(env, specs[i].format);

				env->SetObjectArrayElement(array.get(), static_cast<jsize>(i), info.get());
			}

			return array;
		}

		JavaAudioCodecInfoClass::JavaAudioCodecInfoClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG_AUDIO_CODEC"AudioCodecInfo");

			ctor = GetMethod(env, cls, "<init>", "(" STRING_SIG "II" MAP_SIG ")V");
		}
	}
}
