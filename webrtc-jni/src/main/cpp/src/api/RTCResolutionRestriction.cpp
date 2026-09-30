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

#include "api/RTCResolutionRestriction.h"
#include "JavaClasses.h"
#include "JavaObject.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	namespace RTCResolutionRestriction
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::Resolution & resolution)
		{
			const auto javaClass = JavaClasses::get<JavaRTCResolutionRestrictionClass>(env);

			jobject object = env->NewObject(javaClass->cls, javaClass->ctor,
				static_cast<jint>(resolution.width), static_cast<jint>(resolution.height));

			ExceptionCheck(env);

			return JavaLocalRef<jobject>(env, object);
		}

		webrtc::Resolution toNative(JNIEnv * env, const JavaRef<jobject> & restriction)
		{
			const auto javaClass = JavaClasses::get<JavaRTCResolutionRestrictionClass>(env);

			JavaObject obj(env, restriction);

			webrtc::Resolution resolution;
			resolution.width = obj.getInt(javaClass->maxWidth);
			resolution.height = obj.getInt(javaClass->maxHeight);

			return resolution;
		}

		JavaRTCResolutionRestrictionClass::JavaRTCResolutionRestrictionClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG"RTCResolutionRestriction");

			ctor = GetMethod(env, cls, "<init>", "(II)V");

			maxWidth = GetFieldID(env, cls, "maxWidth", "I");
			maxHeight = GetFieldID(env, cls, "maxHeight", "I");
		}
	}
}
