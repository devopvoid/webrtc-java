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

#include "api/RTCPeerConnectionIceErrorEvent.h"
#include "JavaClasses.h"
#include "JavaString.h"
#include "JavaObject.h"
#include "JavaPrimitive.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	namespace RTCPeerConnectionIceErrorEvent
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const std::string & address, const int & port, const std::string & url, const int & error_code, const std::string & error_text)
		{
			const auto javaClass = JavaClasses::get<JavaRTCPeerConnectionIceErrorEventClass>(env);

			jobject jEvent = env->NewObject(javaClass->cls, javaClass->ctor,
				JavaString::toJava(env, address).get(),
				Integer::create(env, port).get(),
				JavaString::toJava(env, url).get(),
				error_code,
				JavaString::toJava(env, error_text).get());

			return JavaLocalRef<jobject>(env, jEvent);
		}

		JavaRTCPeerConnectionIceErrorEventClass::JavaRTCPeerConnectionIceErrorEventClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG"RTCPeerConnectionIceErrorEvent");

			ctor = GetMethod(env, cls, "<init>", "(" STRING_SIG INTEGER_SIG STRING_SIG "I" STRING_SIG ")V");
		}
	}
}
