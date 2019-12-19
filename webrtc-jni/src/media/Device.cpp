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

#include "media/Device.h"
#include "JavaClasses.h"
#include "JavaString.h"
#include "JNI_WebRTC.h"

namespace jni
{
	namespace Device
	{
		JavaLocalRef<jobject> toJavaAudioDevice(JNIEnv * env, std::string name, std::string guid)
		{
			const auto javaClass = JavaClasses::get<JavaAudioDeviceClass>(env);

			jobject obj = env->NewObject(javaClass->cls, javaClass->ctor,
				JavaString::toJava(env, name).get(),
				JavaString::toJava(env, guid).get());

			return JavaLocalRef<jobject>(env, obj);
		}

		JavaLocalRef<jobject> toJavaVideoDevice(JNIEnv * env, std::string name, std::string guid)
		{
			const auto javaClass = JavaClasses::get<JavaVideoDeviceClass>(env);

			jobject obj = env->NewObject(javaClass->cls, javaClass->ctor,
				JavaString::toJava(env, name).get(),
				JavaString::toJava(env, guid).get());

			return JavaLocalRef<jobject>(env, obj);
		}

		JavaAudioDeviceClass::JavaAudioDeviceClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG_AUDIO"AudioDevice");

			ctor = GetMethod(env, cls, "<init>", "(" STRING_SIG STRING_SIG "I)V");

			guid = GetFieldID(env, cls, "guid", STRING_SIG);
		}

		JavaVideoDeviceClass::JavaVideoDeviceClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG_VIDEO"VideoDevice");

			ctor = GetMethod(env, cls, "<init>", "(" STRING_SIG STRING_SIG "I)V");

			guid = GetFieldID(env, cls, "guid", STRING_SIG);
		}
	}
}