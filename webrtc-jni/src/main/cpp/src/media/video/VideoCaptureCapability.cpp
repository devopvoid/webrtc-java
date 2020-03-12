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

#include "media/video/VideoCaptureCapability.h"
#include "JavaClasses.h"
#include "JNI_WebRTC.h"

namespace jni
{
	namespace avdev
	{
		bool VideoCaptureCapability::operator<(const VideoCaptureCapability & other) const
		{
			if (width > other.width) {
				return true;
			}
			if (height > other.height) {
				return true;
			}
			if (maxFPS > other.maxFPS) {
				return true;
			}
			if (videoType > other.videoType) {
                return true;
            }
            return false;
		}
	}

	namespace VideoCaptureCapability
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const avdev::VideoCaptureCapability & capability)
		{
			const auto javaClass = JavaClasses::get<JavaVideoCaptureCapabilityClass>(env);

			jobject obj = env->NewObject(javaClass->cls, javaClass->ctor,
				static_cast<jint>(capability.width),
				static_cast<jint>(capability.height),
				static_cast<jint>(capability.maxFPS));

			return JavaLocalRef<jobject>(env, obj);
		}

		JavaVideoCaptureCapabilityClass::JavaVideoCaptureCapabilityClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG_VIDEO"VideoCaptureCapability");

			ctor = GetMethod(env, cls, "<init>", "(III)V");
		}
	}
}