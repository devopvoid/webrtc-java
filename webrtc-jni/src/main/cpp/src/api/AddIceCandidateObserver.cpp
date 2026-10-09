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

#include "api/AddIceCandidateObserver.h"
#include "api/WebRTCUtils.h"
#include "JavaClasses.h"
#include "JavaString.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

#include <utility>

namespace jni
{
	AddIceCandidateObserver::AddIceCandidateObserver(JNIEnv * env, const JavaGlobalRef<jobject> & observer) :
		observer(observer),
		javaClass(JavaClasses::get<JavaAddIceCandidateObserverClass>(env))
	{
	}

	AddIceCandidateObserver::~AddIceCandidateObserver()
	{
		// WebRTC drops an operation it has queued, together with its callback,
		// when the peer connection goes away before running it.
		Notify("[INVALID_STATE] AddIceCandidate was discarded before completion");
	}

	void AddIceCandidateObserver::OnComplete(webrtc::RTCError error) noexcept
	{
		try {
			if (error.ok()) {
				Notify(nullptr);
			}
			else {
				Notify(RTCErrorToString(error).c_str());
			}
		}
		catch (...) {
			Notify("[INTERNAL_ERROR] Could not report the AddIceCandidate result");
		}
	}

	void AddIceCandidateObserver::Notify(const char * error) noexcept
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

	AddIceCandidateObserver::JavaAddIceCandidateObserverClass::JavaAddIceCandidateObserverClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG"AddIceCandidateObserver");

		onSuccess = GetMethod(env, cls, "onSuccess", "()V");
		onFailure = GetMethod(env, cls, "onFailure", "(" STRING_SIG ")V");
	}
}
