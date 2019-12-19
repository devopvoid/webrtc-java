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

#include "api/RTCRtpHeaderExtParameters.h"
#include "JavaClasses.h"
#include "JavaObject.h"
#include "JavaString.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	namespace RTCRtpHeaderExtParameters
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::RtpHeaderExtensionParameters & parameters)
		{
			const auto javaClass = JavaClasses::get<JavaRTCRtpHeaderExtParametersClass>(env);

			JavaLocalRef<jstring> uri = JavaString::toJava(env, parameters.uri);
			jint id = static_cast<jint>(parameters.id);
			jboolean encrypted = static_cast<jboolean>(parameters.encrypt);

			jobject object = env->NewObject(javaClass->cls, javaClass->ctor, uri.get(), id, encrypted);
			ExceptionCheck(env);

			return JavaLocalRef<jobject>(env, object);
		}

		webrtc::RtpHeaderExtensionParameters toNative(JNIEnv * env, const JavaRef<jobject> & parameters)
		{
			const auto javaClass = JavaClasses::get<JavaRTCRtpHeaderExtParametersClass>(env);

			JavaObject obj(env, parameters);

			webrtc::RtpHeaderExtensionParameters params;
			params.uri = JavaString::toNative(env, obj.getString(javaClass->uri));
			params.id = static_cast<int>(obj.getInt(javaClass->id));
			params.encrypt = static_cast<bool>(obj.getBoolean(javaClass->encrypted));

			return params;
		}

		JavaRTCRtpHeaderExtParametersClass::JavaRTCRtpHeaderExtParametersClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG"RTCRtpHeaderExtensionParameters");

			ctor = GetMethod(env, cls, "<init>", "(" STRING_SIG "IZ)V");

			uri = GetFieldID(env, cls, "uri", STRING_SIG);
			id = GetFieldID(env, cls, "id", "I");
			encrypted = GetFieldID(env, cls, "encrypted", "Z");
		}
	}
}