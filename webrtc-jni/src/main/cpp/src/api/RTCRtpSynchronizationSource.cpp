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

#include "api/RTCRtpSynchronizationSource.h"
#include "api/RTCRtpContributingSource.h"
#include "JavaRef.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	namespace RTCRtpSynchronizationSource
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::RtpSource & source)
		{
			const auto javaClass = JavaClasses::get<JavaRTCRtpSynchronizationSourceClass>(env);

			jlong timestamp = static_cast<jlong>(source.timestamp().us());
			jlong sourceId = static_cast<jlong>(source.source_id());
			jdouble audioLevel = RTCRtpContributingSource::toLinearAudioLevel(source.audio_level());
			jlong rtpTimestamp = static_cast<jlong>(source.rtp_timestamp());
			jboolean voiceActivityFlag = false;

			jobject object = env->NewObject(javaClass->cls, javaClass->ctor,
				timestamp, sourceId, audioLevel, rtpTimestamp, voiceActivityFlag);

			return JavaLocalRef<jobject>(env, object);
		}

		JavaRTCRtpSynchronizationSourceClass::JavaRTCRtpSynchronizationSourceClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG"RTCRtpSynchronizationSource");

			ctor = GetMethod(env, cls, "<init>", "(JJDJZ)V");
		}
	}
}
