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

#include "api/RTCDataChannelObserver.h"
#include "JavaFactories.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	RTCDataChannelObserver::RTCDataChannelObserver(JNIEnv * env, const JavaGlobalRef<jobject> & observer) :
		observer(observer),
		bufferFactory(std::make_unique<DataBufferFactory>(env, PKG"RTCDataChannelBuffer")),
		javaClass(JavaClasses::get<JavaRTCDataChannelObserverClass>(env))
	{
	}

	void RTCDataChannelObserver::OnStateChange()
	{
		JNIEnv * env = AttachCurrentThread();

		env->CallVoidMethod(observer, javaClass->onStateChange);
	}

	void RTCDataChannelObserver::OnMessage(const webrtc::DataBuffer & buffer)
	{
		JNIEnv * env = AttachCurrentThread();

		JavaLocalRef<jobject> jBuffer = bufferFactory->create(env, &buffer);

		env->CallVoidMethod(observer, javaClass->onMessage, jBuffer.release());
	}

	RTCDataChannelObserver::JavaRTCDataChannelObserverClass::JavaRTCDataChannelObserverClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG"RTCDataChannelObserver");

		onStateChange = GetMethod(env, cls, "onStateChange", "()V");
		onMessage = GetMethod(env, cls, "onMessage", "(L" PKG "RTCDataChannelBuffer;)V");
	}
}