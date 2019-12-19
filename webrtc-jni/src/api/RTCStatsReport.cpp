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

#include "api/RTCStatsReport.h"
#include "api/RTCStats.h"
#include "JavaClasses.h"
#include "JavaHashMap.h"
#include "JavaString.h"
#include "JNI_WebRTC.h"

namespace jni
{
	namespace RTCStatsReport
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const rtc::scoped_refptr<const webrtc::RTCStatsReport> & report)
		{
			const auto javaClass = JavaClasses::get<JavaRTCStatsReportClass>(env);

			JavaHashMap statsMap(env);

			for (const auto & stats : *report) {
				JavaLocalRef<jstring> key = JavaString::toJava(env, stats.id());
				JavaLocalRef<jobject> value = RTCStats::toJava(env, stats);

				statsMap.put(key, value);
			}

			jobject obj = env->NewObject(javaClass->cls, javaClass->ctor, ((JavaLocalRef<jobject>)statsMap).get());

			return JavaLocalRef<jobject>(env, obj);
		}

		JavaRTCStatsReportClass::JavaRTCStatsReportClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG"RTCStatsReport");

			ctor = GetMethod(env, cls, "<init>", "(" MAP_SIG ")V");
		}
	}
}