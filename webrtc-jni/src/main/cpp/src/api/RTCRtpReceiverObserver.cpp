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

#include "api/RTCRtpReceiverObserver.h"
#include "api/WebRTCUtils.h"
#include "JavaClasses.h"
#include "JavaEnums.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	RTCRtpReceiverObserver::RTCRtpReceiverObserver(JNIEnv * env, const JavaGlobalRef<jobject> & observer) :
		observer(observer),
		javaClass(JavaClasses::get<JavaRTCRtpReceiverObserverClass>(env))
	{
	}

	void RTCRtpReceiverObserver::OnFirstPacketReceived(webrtc::MediaType media_type)
	{
		JNIEnv * env = AttachCurrentThread();

		if (env == nullptr) {
			return;
		}

		try {
			auto jMediaType = JavaEnums::toJava(env, media_type);

			env->CallVoidMethod(observer, javaClass->onFirstPacketReceived, jMediaType.get());
		}
		catch (...) {
			ThrowCxxJavaException(env);
		}

		ReportPendingException(env);
	}

	RTCRtpReceiverObserver::JavaRTCRtpReceiverObserverClass::JavaRTCRtpReceiverObserverClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG"RTCRtpReceiverObserver");

		onFirstPacketReceived = GetMethod(env, cls, "onFirstPacketReceived", "(L" PKG_MEDIA "MediaType;)V");
	}
}
