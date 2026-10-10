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

#include "api/RTCRtpSenderObserver.h"
#include "api/WebRTCUtils.h"
#include "JavaClasses.h"
#include "JavaEnums.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	RTCRtpSenderObserver::RTCRtpSenderObserver(JNIEnv * env, const JavaGlobalRef<jobject> & observer) :
		observer(observer),
		javaClass(JavaClasses::get<JavaRTCRtpSenderObserverClass>(env))
	{
	}

	void RTCRtpSenderObserver::OnFirstPacketSent(webrtc::MediaType media_type)
	{
		JNIEnv * env = AttachCurrentThread();

		if (env == nullptr) {
			return;
		}

		try {
			auto jMediaType = JavaEnums::toJava(env, media_type);

			env->CallVoidMethod(observer, javaClass->onFirstPacketSent, jMediaType.get());
		}
		catch (...) {
			ThrowCxxJavaException(env);
		}

		ReportPendingException(env);
	}

	RTCRtpSenderObserver::JavaRTCRtpSenderObserverClass::JavaRTCRtpSenderObserverClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG"RTCRtpSenderObserver");

		onFirstPacketSent = GetMethod(env, cls, "onFirstPacketSent", "(L" PKG_MEDIA "MediaType;)V");
	}
}
