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

#include "api/RTCAnswerOptions.h"
#include "JavaClasses.h"
#include "JavaEnums.h"
#include "JavaObject.h"
#include "JavaString.h"
#include "Exception.h"
#include "JNI_WebRTC.h"

namespace jni
{
	namespace RTCAnswerOptions
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::PeerConnectionInterface::RTCOfferAnswerOptions & nativeType)
		{
			const auto javaClass = JavaClasses::get<JavaRTCAnswerOptionsClass>(env);

			jobject obj = env->NewObject(javaClass->cls, javaClass->ctor);

			env->SetBooleanField(obj, javaClass->vad, nativeType.voice_activity_detection);

			return JavaLocalRef<jobject>(env, obj);
		}

		webrtc::PeerConnectionInterface::RTCOfferAnswerOptions toNative(JNIEnv * env, const JavaRef<jobject> & javaType)
		{
			const auto javaClass = JavaClasses::get<JavaRTCAnswerOptionsClass>(env);

			JavaObject obj(env, javaType);

			webrtc::PeerConnectionInterface::RTCOfferAnswerOptions options;
			options.voice_activity_detection = obj.getBoolean(javaClass->vad);

			return options;
		}

		JavaRTCAnswerOptionsClass::JavaRTCAnswerOptionsClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG"RTCAnswerOptions");

			ctor = GetMethod(env, cls, "<init>", "()V");

			vad = GetFieldID(env, cls, "voiceActivityDetection", "Z");
		}
	}
}