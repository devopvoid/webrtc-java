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

#include "api/RTCDtlsTransportObserver.h"
#include "api/WebRTCUtils.h"
#include "JavaEnums.h"
#include "JavaRef.h"
#include "JavaString.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	RTCDtlsTransportObserver::RTCDtlsTransportObserver(JNIEnv * env, const JavaGlobalRef<jobject> & observer) :
		observer(observer),
		javaClass(JavaClasses::get<JavaRTCDtlsTransportObserverClass>(env))
	{
	}

	void RTCDtlsTransportObserver::OnStateChange(webrtc::DtlsTransportInformation info)
	{
		JNIEnv * env = AttachCurrentThread();
		
		auto state = JavaEnums::toJava(env, info.state());

		env->CallVoidMethod(observer, javaClass->onStateChange, state.get());
	}

	void RTCDtlsTransportObserver::OnError(webrtc::RTCError error)
	{
		JNIEnv * env = AttachCurrentThread();

		JavaLocalRef<jstring> errorMessage = JavaString::toJava(env, RTCErrorToString(error));

		env->CallVoidMethod(observer, javaClass->onError, errorMessage.get());
	}

	RTCDtlsTransportObserver::JavaRTCDtlsTransportObserverClass::JavaRTCDtlsTransportObserverClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG"RTCDtlsTransportObserver");

		onStateChange = GetMethod(env, cls, "onStateChange", "(L" PKG "RTCDtlsTransportState;)V");
		onError = GetMethod(env, cls, "onError", "(" STRING_SIG ")V");
	}
}