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

#include "api/RTCRtpCodecCapability.h"
#include "JavaEnums.h"
#include "JavaHashMap.h"
#include "JavaObject.h"
#include "JavaString.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	namespace RTCRtpCodecCapability
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::RtpCodecCapability & capability)
		{
			JavaLocalRef<jobject> mediaType = JavaEnums::toJava(env, capability.kind);
			JavaLocalRef<jstring> codecName = JavaString::toJava(env, capability.name);
			jint clockRate = static_cast<jint>(capability.clock_rate.value_or(0));
			jint channels = static_cast<jint>(capability.num_channels.value_or(0));

			JavaHashMap paramMap(env);

			for (const std::pair<std::string, std::string> & param : capability.parameters) {
				JavaLocalRef<jstring> key = JavaString::toJava(env, param.first);
				JavaLocalRef<jstring> value = JavaString::toJava(env, param.second);

				paramMap.put(key, value);
			}

			const auto javaClass = JavaClasses::get<JavaRTCRtpCodecCapabilityClass>(env);

			JavaLocalRef<jobject> fmtMap = paramMap;

			jobject object = env->NewObject(javaClass->cls, javaClass->ctor,
				mediaType.get(), codecName.get(), clockRate, channels, fmtMap.get());

			ExceptionCheck(env);

			return JavaLocalRef<jobject>(env, object);
		}

		webrtc::RtpCodecCapability toNative(JNIEnv * env, const JavaRef<jobject> & capability)
		{
			const auto javaClass = JavaClasses::get<JavaRTCRtpCodecCapabilityClass>(env);

			JavaObject obj(env, capability);

			webrtc::RtpCodecCapability codecCapability;
			
			codecCapability.kind = JavaEnums::toNative<cricket::MediaType>(env, obj.getObject(javaClass->mediaType));
			codecCapability.name = JavaString::toNative(env, obj.getString(javaClass->name));
			codecCapability.clock_rate = obj.getInt<int>(javaClass->clockRate);
			codecCapability.num_channels = obj.getInt<int>(javaClass->channels);
			
			for (const auto & entry : JavaHashMap(env, obj.getObject(javaClass->sdpFmtp))) {
				std::string key = JavaString::toNative(env, static_java_ref_cast<jstring>(env, entry.first));
				std::string value = JavaString::toNative(env, static_java_ref_cast<jstring>(env, entry.second));

				codecCapability.parameters.emplace(key, value);
			}

			return codecCapability;
		}

		JavaRTCRtpCodecCapabilityClass::JavaRTCRtpCodecCapabilityClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG"RTCRtpCodecCapability");

			ctor = GetMethod(env, cls, "<init>", "(L" PKG_MEDIA "MediaType;" STRING_SIG "II" MAP_SIG ")V");

			mediaType = GetFieldID(env, cls, "mediaType", "L" PKG_MEDIA "MediaType;");
			name = GetFieldID(env, cls, "name", STRING_SIG);
			clockRate = GetFieldID(env, cls, "clockRate", "I");
			channels = GetFieldID(env, cls, "channels", "I");
			sdpFmtp = GetFieldID(env, cls, "sdpFmtp", MAP_SIG);
		}
	}
}
