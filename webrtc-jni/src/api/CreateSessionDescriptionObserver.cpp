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

#include "api/CreateSessionDescriptionObserver.h"
#include "api/RTCSessionDescription.h"
#include "api/WebRTCUtils.h"
#include "JavaString.h"
#include "JNI_WebRTC.h"

namespace jni
{
	CreateSessionDescriptionObserver::CreateSessionDescriptionObserver(JNIEnv * env, const JavaGlobalRef<jobject> & observer) :
		observer(observer),
		javaClass(JavaClasses::get<JavaCreateSessionDescObserverClass>(env))
	{
	}

	void CreateSessionDescriptionObserver::OnSuccess(webrtc::SessionDescriptionInterface * desc)
	{
		JNIEnv * env = AttachCurrentThread();

		JavaLocalRef<jobject> javaDesc = jni::RTCSessionDescription::toJava(env, desc);

		env->CallVoidMethod(observer, javaClass->onSuccess, javaDesc.get());
	}

	void CreateSessionDescriptionObserver::OnFailure(webrtc::RTCError error)
	{
		JNIEnv * env = AttachCurrentThread();

		JavaLocalRef<jstring> errorMessage = JavaString::toJava(env, RTCErrorToString(error));

		env->CallVoidMethod(observer, javaClass->onFailure, errorMessage.get());
	}

	CreateSessionDescriptionObserver::JavaCreateSessionDescObserverClass::JavaCreateSessionDescObserverClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG"CreateSessionDescriptionObserver");

		onSuccess = GetMethod(env, cls, "onSuccess", "(L" PKG "RTCSessionDescription;)V");
		onFailure = GetMethod(env, cls, "onFailure", "(" STRING_SIG ")V");
	}
}