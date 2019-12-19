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

#include "api/RTCRtcpParameters.h"
#include "JavaClasses.h"
#include "JavaObject.h"
#include "JavaString.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	namespace RTCRtcpParameters
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::RtcpParameters & parameters)
		{
			const auto javaClass = JavaClasses::get<JavaRTCRtcpParametersClass>(env);

			JavaLocalRef<jstring> cName = JavaString::toJava(env, parameters.cname);
			jboolean reducedSize = static_cast<jboolean>(parameters.reduced_size);

			jobject object = env->NewObject(javaClass->cls, javaClass->ctor, cName.get(), reducedSize);
			ExceptionCheck(env);

			return JavaLocalRef<jobject>(env, object);
		}

		webrtc::RtcpParameters toNative(JNIEnv * env, const JavaRef<jobject> & parameters)
		{
			const auto javaClass = JavaClasses::get<JavaRTCRtcpParametersClass>(env);

			JavaObject obj(env, parameters);

			JavaLocalRef<jstring> cName = obj.getString(javaClass->cName);
			jboolean reducedSize = obj.getBoolean(javaClass->reducedSize);

			webrtc::RtcpParameters params;
			params.cname = JavaString::toNative(env, cName);
			params.reduced_size = static_cast<bool>(reducedSize);

			return params;
		}

		JavaRTCRtcpParametersClass::JavaRTCRtcpParametersClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG"RTCRtcpParameters");

			ctor = GetMethod(env, cls, "<init>", "(" STRING_SIG "Z)V");

			cName = GetFieldID(env, cls, "cName", STRING_SIG);
			reducedSize = GetFieldID(env, cls, "reducedSize", "Z");
		}
	}
}