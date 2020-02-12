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

#include "api/RTCSessionDescription.h"
#include "JavaClasses.h"
#include "JavaEnums.h"
#include "JavaObject.h"
#include "JavaString.h"
#include "Exception.h"
#include "JNI_WebRTC.h"

#include <string>

namespace jni
{
	namespace RTCSessionDescription
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::SessionDescriptionInterface * nativeType)
		{
			const auto javaClass = JavaClasses::get<JavaRTCSessionDescriptionClass>(env);

			std::string sdpStr;
			nativeType->ToString(&sdpStr);
			
			JavaLocalRef<jobject> type = JavaEnums::toJava(env, nativeType->GetType());
			JavaLocalRef<jstring> sdp = JavaString::toJava(env, sdpStr);

			jobject obj = env->NewObject(javaClass->cls, javaClass->ctor, type.get(), sdp.get());

			return JavaLocalRef<jobject>(env, obj);
		}

		std::unique_ptr<webrtc::SessionDescriptionInterface> toNative(JNIEnv * env, const JavaRef<jobject>& javaType)
		{
			const auto javaClass = JavaClasses::get<JavaRTCSessionDescriptionClass>(env);

			JavaObject obj(env, javaType);

			auto jType = obj.getObject(javaClass->sdpType);
			auto jSdp = obj.getString(javaClass->sdp);

			auto type = JavaEnums::toNative<webrtc::SdpType>(env, jType);
			std::string sdp = JavaString::toNative(env, jSdp);
			webrtc::SdpParseError error;

			auto desc = webrtc::CreateSessionDescription(type, sdp, &error);

			if (desc == nullptr) {
				throw Exception("Create session description failed: %s [%s]", error.description.c_str(), error.line.c_str());
			}

			return desc;
		}

		JavaRTCSessionDescriptionClass::JavaRTCSessionDescriptionClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG"RTCSessionDescription");

			ctor = GetMethod(env, cls, "<init>", "(L" PKG "RTCSdpType;" STRING_SIG ")V");

			sdpType = GetFieldID(env, cls, "sdpType", "L" PKG "RTCSdpType;");
			sdp = GetFieldID(env, cls, "sdp", STRING_SIG);
		}
	}
}