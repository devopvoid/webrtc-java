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

#include "api/RTCRtpCapabilities.h"
#include "api/RTCRtpCodecCapability.h"
#include "api/RTCRtpHeaderExtensionCapability.h"
#include "JavaArrayList.h"
#include "JavaClasses.h"
#include "JavaList.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	namespace RTCRtpCapabilities
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::RtpCapabilities & capabilities)
		{
			const auto javaClass = JavaClasses::get<JavaRTCRtpCapabilitiesClass>(env);

			JavaLocalRef<jobject> codecs = JavaList::toArrayList(env, capabilities.codecs, &RTCRtpCodecCapability::toJava);
			JavaLocalRef<jobject> headerExtensions = JavaList::toArrayList(env, capabilities.header_extensions, &RTCRtpHeaderExtensionCapability::toJava);

			jobject object = env->NewObject(javaClass->cls, javaClass->ctor,
				codecs.get(), headerExtensions.get());

			return JavaLocalRef<jobject>(env, object);
		}

		JavaRTCRtpCapabilitiesClass::JavaRTCRtpCapabilitiesClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG"RTCRtpCapabilities");

			ctor = GetMethod(env, cls, "<init>", "(" LIST_SIG LIST_SIG ")V");
		}
	}
}
