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

#include "api/RTCDtmfSender.h"
#include "JavaClasses.h"
#include "JavaObject.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	namespace RTCDtmfSender
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env)
		{
			const auto javaClass = JavaClasses::get<JavaRTCDtmfSenderClass>(env);

			jobject jDtmfSender = env->NewObject(javaClass->cls, javaClass->ctor);

			return JavaLocalRef<jobject>(env, jDtmfSender);
		}

		JavaRTCDtmfSenderClass::JavaRTCDtmfSenderClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG"RTCDtmfSender");

			ctor = GetMethod(env, cls, "<init>", "()V");
		}
	}
}
