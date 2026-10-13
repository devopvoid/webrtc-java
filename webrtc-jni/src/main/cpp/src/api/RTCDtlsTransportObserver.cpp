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
#include "api/JavaCallbackThread.h"
#include "api/WebRTCUtils.h"
#include "JavaEnums.h"
#include "JavaRef.h"
#include "JavaString.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	RTCDtlsTransportObserver::RTCDtlsTransportObserver(JNIEnv * env, const JavaGlobalRef<jobject> & observer) :
		target(std::make_shared<Target>(observer, JavaClasses::get<JavaRTCDtlsTransportObserverClass>(env)))
	{
	}

	RTCDtlsTransportObserver::Target::Target(const JavaGlobalRef<jobject> & observer,
		std::shared_ptr<JavaRTCDtlsTransportObserverClass> javaClass) :
		active(true),
		observer(observer),
		javaClass(std::move(javaClass))
	{
	}

	RTCDtlsTransportObserver::~RTCDtlsTransportObserver()
	{
		std::lock_guard<std::recursive_mutex> lock(target->mutex);

		target->active = false;
	}

	void RTCDtlsTransportObserver::OnStateChange(webrtc::DtlsTransportInformation info)
	{
		JavaCallbackThread::Post([target = target, state = info.state()](JNIEnv * env) {
			std::lock_guard<std::recursive_mutex> lock(target->mutex);

			if (!target->active) {
				return;
			}

			try {
				auto jState = JavaEnums::toJava(env, state);

				env->CallVoidMethod(target->observer, target->javaClass->onStateChange, jState.get());
			}
			catch (...) {
				ThrowCxxJavaException(env);
			}

			ReportPendingException(env);
		});
	}

	void RTCDtlsTransportObserver::OnError(webrtc::RTCError error)
	{
		JavaCallbackThread::Post([target = target, message = RTCErrorToString(error)](JNIEnv * env) {
			std::lock_guard<std::recursive_mutex> lock(target->mutex);

			if (!target->active) {
				return;
			}

			try {
				JavaLocalRef<jstring> errorMessage = JavaString::toJava(env, message);

				env->CallVoidMethod(target->observer, target->javaClass->onError, errorMessage.get());
			}
			catch (...) {
				ThrowCxxJavaException(env);
			}

			ReportPendingException(env);
		});
	}

	RTCDtlsTransportObserver::JavaRTCDtlsTransportObserverClass::JavaRTCDtlsTransportObserverClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG"RTCDtlsTransportObserver");

		onStateChange = GetMethod(env, cls, "onStateChange", "(L" PKG "RTCDtlsTransportState;)V");
		onError = GetMethod(env, cls, "onError", "(" STRING_SIG ")V");
	}
}
