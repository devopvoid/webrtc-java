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

#include "api/RTCRtpParameters.h"
#include "api/RTCRtpCodecParameters.h"
#include "api/RTCRtpHeaderExtension.h"
#include "api/RTCRtcpParameters.h"
#include "JavaArrayList.h"
#include "JavaClasses.h"
#include "JavaIterable.h"
#include "JavaList.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	namespace RTCRtpParameters
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::RtpParameters & parameters)
		{
			const auto javaClass = JavaClasses::get<JavaRTCRtpParametersClass>(env);

			JavaLocalRef<jobject> rtcp = RTCRtcpParameters::toJava(env, parameters.rtcp);

			jobject object = env->NewObject(javaClass->cls, javaClass->ctor);
			env->SetObjectField(object, javaClass->rtcp, rtcp.get());
			env->SetObjectField(object, javaClass->headerExtensions, JavaList::toArrayList(env, parameters.header_extensions, &RTCRtpHeaderExtension::toJava));
			env->SetObjectField(object, javaClass->codecs, JavaList::toArrayList(env, parameters.codecs, &RTCRtpCodecParameters::toJava));

			return JavaLocalRef<jobject>(env, object);
		}

		webrtc::RtpParameters toNative(JNIEnv * env, const JavaRef<jobject> & javaType)
		{
			const auto javaClass = JavaClasses::get<JavaRTCRtpParametersClass>(env);

			JavaObject obj(env, javaType);

			JavaLocalRef<jobject> headerExtensions = obj.getObject(javaClass->headerExtensions);
			JavaLocalRef<jobject> rtcp = obj.getObject(javaClass->rtcp);
			JavaLocalRef<jobject> codecs = obj.getObject(javaClass->codecs);

			webrtc::RtpParameters params;
			params.rtcp = RTCRtcpParameters::toNative(env, rtcp);
			params.header_extensions = JavaList::toVector(env, headerExtensions, &RTCRtpHeaderExtension::toNative);
			params.codecs = JavaList::toVector(env, codecs, &RTCRtpCodecParameters::toNative);

			return params;
		}

		JavaRTCRtpParametersClass::JavaRTCRtpParametersClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG"RTCRtpParameters");

			ctor = GetMethod(env, cls, "<init>", "()V");

			headerExtensions = GetFieldID(env, cls, "headerExtensions", LIST_SIG);
			rtcp = GetFieldID(env, cls, "rtcp", "L" PKG "RTCRtcpParameters;");
			codecs = GetFieldID(env, cls, "codecs", LIST_SIG);
		}
	}
}
