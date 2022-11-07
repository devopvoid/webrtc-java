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

#include "api/RTCRtpCodecParameters.h"
#include "JavaEnums.h"
#include "JavaHashMap.h"
#include "JavaObject.h"
#include "JavaPrimitive.h"
#include "JavaRef.h"
#include "JavaString.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	namespace RTCRtpCodecParameters
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::RtpCodecParameters & parameters)
		{
			jint payloadType = static_cast<jint>(parameters.payload_type);
			JavaLocalRef<jobject> mediaType = JavaEnums::toJava(env, parameters.kind);
			JavaLocalRef<jstring> codecName = JavaString::toJava(env, parameters.name);
			JavaLocalRef<jobject> clockRate = nullptr;
			JavaLocalRef<jobject> channels = nullptr;
			JavaHashMap paramMap(env);

			for (const std::pair<std::string, std::string> & param : parameters.parameters) {
				JavaLocalRef<jstring> key = JavaString::toJava(env, param.first);
				JavaLocalRef<jstring> value = JavaString::toJava(env, param.second);

				paramMap.put(key, value);
			}

			if (parameters.clock_rate.has_value()) {
				clockRate = Integer::create(env, parameters.clock_rate.value());
			}
			if (parameters.num_channels.has_value()) {
				channels = Integer::create(env, parameters.num_channels.value());
			}

			const auto javaClass = JavaClasses::get<JavaRTCRtpCodecParametersClass>(env);

			jobject object = env->NewObject(javaClass->cls, javaClass->ctor, payloadType, mediaType.get(), codecName.get(), clockRate.get(), channels.get(), ((JavaLocalRef<jobject>)paramMap).get());
			ExceptionCheck(env);

			return JavaLocalRef<jobject>(env, object);
		}

		webrtc::RtpCodecParameters toNative(JNIEnv * env, const JavaRef<jobject> & parameters)
		{
			const auto javaClass = JavaClasses::get<JavaRTCRtpCodecParametersClass>(env);

			JavaObject obj(env, parameters);

			webrtc::RtpCodecParameters params;
			params.payload_type = obj.getInt<int>(javaClass->payloadType);
			params.kind = JavaEnums::toNative<cricket::MediaType>(env, obj.getObject(javaClass->mediaType));
			params.name = JavaString::toNative(env, obj.getString(javaClass->codecName));

			for (const auto & entry : JavaHashMap(env, obj.getObject(javaClass->parameters))) {
				std::string key = JavaString::toNative(env, static_java_ref_cast<jstring>(env, entry.first));
				std::string value = JavaString::toNative(env, static_java_ref_cast<jstring>(env, entry.second));

				params.parameters.emplace(key, value);
			}

			auto clockRate = obj.getObject(javaClass->clockRate);
			auto channels = obj.getObject(javaClass->channels);

			if (clockRate.get()) {
				params.clock_rate = Integer::getValue(env, clockRate);
			}
			if (channels.get()) {
				params.num_channels = Integer::getValue(env, channels);
			}

			return params;
		}

		JavaRTCRtpCodecParametersClass::JavaRTCRtpCodecParametersClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG"RTCRtpCodecParameters");

			ctor = GetMethod(env, cls, "<init>", "(IL" PKG_MEDIA "MediaType;" STRING_SIG INTEGER_SIG INTEGER_SIG MAP_SIG ")V");

			payloadType = GetFieldID(env, cls, "payloadType", "I");
			mediaType = GetFieldID(env, cls, "mediaType", "L" PKG_MEDIA "MediaType;");
			codecName = GetFieldID(env, cls, "codecName", STRING_SIG);
			clockRate = GetFieldID(env, cls, "clockRate", INTEGER_SIG);
			channels = GetFieldID(env, cls, "channels", INTEGER_SIG);
			parameters = GetFieldID(env, cls, "parameters", MAP_SIG);
		}
	}
}
