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

#include "api/SetSessionDescriptionObserver.h"
#include "api/WebRTCUtils.h"
#include "JavaString.h"
#include "JNI_WebRTC.h"
#include "JavaUtils.h"

#include <utility>

namespace jni
{
	SetSessionDescriptionObserver::SetSessionDescriptionObserver(JNIEnv * env, const JavaGlobalRef<jobject> & observer) :
		observer(observer),
		javaClass(JavaClasses::get<JavaSetSessionDescObserverClass>(env))
	{
	}

	SetSessionDescriptionObserver::~SetSessionDescriptionObserver()
	{
		// WebRTC posts the result to the signaling thread and drops it, together
		// with this observer, when the peer connection goes away before it runs.
		Notify("[INVALID_STATE] Setting the session description was discarded before completion");
	}

	void SetSessionDescriptionObserver::OnSuccess()
	{
		Notify(nullptr);
	}

	void SetSessionDescriptionObserver::OnFailure(webrtc::RTCError error)
	{
		try {
			Notify(RTCErrorToString(error).c_str());
		}
		catch (...) {
			Notify("[INTERNAL_ERROR] Could not report the session description result");
		}
	}

	void SetSessionDescriptionObserver::Notify(const char * error) noexcept
	{
		JavaGlobalRef<jobject> callback(std::move(observer));

		if (!callback.get()) {
			return;
		}

		JNIEnv * env = AttachCurrentThread();

		if (env == nullptr) {
			return;
		}

		if (error == nullptr) {
			env->CallVoidMethod(callback.get(), javaClass->onSuccess);
		}
		else {
			JavaLocalRef<jstring> message(env, env->NewStringUTF(error));

			if (!env->ExceptionCheck()) {
				env->CallVoidMethod(callback.get(), javaClass->onFailure, message.get());
			}
		}

		// A Java exception must not escape into WebRTC's signaling thread.
		ReportPendingException(env);
	}

	SetSessionDescriptionObserver::JavaSetSessionDescObserverClass::JavaSetSessionDescObserverClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG"SetSessionDescriptionObserver");

		onSuccess = GetMethod(env, cls, "onSuccess", "()V");
		onFailure = GetMethod(env, cls, "onFailure", "(" STRING_SIG ")V");
	}
}
