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

namespace jni
{
	SetSessionDescriptionObserver::SetSessionDescriptionObserver(JNIEnv * env, const JavaGlobalRef<jobject> & observer) :
		observer(observer),
		javaClass(JavaClasses::get<JavaSetSessionDescObserverClass>(env))
	{
	}

	void SetSessionDescriptionObserver::OnSuccess()
	{
		JNIEnv * env = AttachCurrentThread();

		env->CallVoidMethod(observer, javaClass->onSuccess);
	}

	void SetSessionDescriptionObserver::OnFailure(webrtc::RTCError error)
	{
		JNIEnv * env = AttachCurrentThread();

		JavaLocalRef<jstring> errorMessage = JavaString::toJava(env, RTCErrorToString(error));

		env->CallVoidMethod(observer, javaClass->onFailure, errorMessage.get());
	}

	SetSessionDescriptionObserver::JavaSetSessionDescObserverClass::JavaSetSessionDescObserverClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG"SetSessionDescriptionObserver");

		onSuccess = GetMethod(env, cls, "onSuccess", "()V");
		onFailure = GetMethod(env, cls, "onFailure", "(" STRING_SIG ")V");
	}
}