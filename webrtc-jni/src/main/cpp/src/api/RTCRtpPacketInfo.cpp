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

#include "api/RTCRtpPacketInfo.h"
#include "JavaClasses.h"
#include "JavaPrimitive.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

#include <vector>

namespace jni
{
	namespace RTCRtpPacketInfo
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::RtpPacketInfo & info)
		{
			const auto javaClass = JavaClasses::get<JavaRTCRtpPacketInfoClass>(env);

			const std::vector<uint32_t> & csrcs = info.csrcs();
			std::vector<jlong> sources(csrcs.begin(), csrcs.end());

			JavaLocalRef<jlongArray> jSources(env, env->NewLongArray(static_cast<jsize>(sources.size())));
			ExceptionCheck(env);

			env->SetLongArrayRegion(jSources.get(), 0, static_cast<jsize>(sources.size()), sources.data());

			JavaLocalRef<jobject> jAudioLevel(env, nullptr);

			if (info.audio_level().has_value()) {
				jAudioLevel = Integer::create(env, static_cast<jint>(*info.audio_level()));
			}

			jlong receiveTimeMs = info.receive_time().IsFinite() ? info.receive_time().ms() : 0;

			jobject object = env->NewObject(javaClass->cls, javaClass->ctor,
				static_cast<jlong>(info.ssrc()), jSources.get(), static_cast<jlong>(info.rtp_timestamp()),
				receiveTimeMs, jAudioLevel.get());
			ExceptionCheck(env);

			return JavaLocalRef<jobject>(env, object);
		}

		JavaLocalRef<jobjectArray> toJavaArray(JNIEnv * env, const webrtc::RtpPacketInfos & infos)
		{
			const auto javaClass = JavaClasses::get<JavaRTCRtpPacketInfoClass>(env);

			JavaLocalRef<jobjectArray> array(env, env->NewObjectArray(static_cast<jsize>(infos.size()), javaClass->cls, nullptr));
			ExceptionCheck(env);

			jsize index = 0;

			for (const webrtc::RtpPacketInfo & info : infos) {
				JavaLocalRef<jobject> jInfo = toJava(env, info);

				env->SetObjectArrayElement(array.get(), index++, jInfo.get());
			}

			return array;
		}

		JavaRTCRtpPacketInfoClass::JavaRTCRtpPacketInfoClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG"RTCRtpPacketInfo");

			ctor = GetMethod(env, cls, "<init>", "(J[JJJLjava/lang/Integer;)V");
		}
	}
}
