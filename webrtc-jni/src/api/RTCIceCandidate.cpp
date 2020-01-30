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

#include "api/RTCIceCandidate.h"
#include "JavaClasses.h"
#include "JavaString.h"
#include "JavaObject.h"
#include "JavaUtils.h"
#include "Exception.h"
#include "JNI_WebRTC.h"

#include "pc/webrtc_sdp.h"

namespace jni
{
	namespace RTCIceCandidate
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::IceCandidateInterface * candidate)
		{
			const auto javaClass = JavaClasses::get<JavaRTCIceCandidateClass>(env);

			std::string sdpStr;
			candidate->ToString(&sdpStr);
			
			jobject jCandidate = env->NewObject(javaClass->cls, javaClass->ctor,
				JavaString::toJava(env, candidate->sdp_mid()).get(),
				candidate->sdp_mline_index(),
				JavaString::toJava(env, sdpStr).get(),
				JavaString::toJava(env, candidate->server_url()).get());

			return JavaLocalRef<jobject>(env, jCandidate);
		}

		JavaLocalRef<jobject> toJavaCricket(JNIEnv * env, const cricket::Candidate & candidate)
		{
			const auto javaClass = JavaClasses::get<JavaRTCIceCandidateClass>(env);

			std::string sdp = webrtc::SdpSerializeCandidate(candidate);

			if (sdp.empty()) {
				throw Exception("Got an empty ICE candidate");
			}

			jobject jCandidate = env->NewObject(javaClass->cls, javaClass->ctor,
				JavaString::toJava(env, candidate.id()).get(),
				candidate.component(),
				JavaString::toJava(env, sdp).get(),
				JavaString::toJava(env, candidate.url()).get());

			return JavaLocalRef<jobject>(env, jCandidate);
		}

		std::unique_ptr<webrtc::IceCandidateInterface> toNative(JNIEnv * env, const JavaRef<jobject> & javaType)
		{
			const auto javaClass = JavaClasses::get<JavaRTCIceCandidateClass>(env);

			JavaObject obj(env, javaType);

			std::string sdp = JavaString::toNative(env, obj.getString(javaClass->sdp));
			std::string sdpMid = JavaString::toNative(env, obj.getString(javaClass->sdpMid));
			int sdpMLineIndex = obj.getInt(javaClass->sdpMLineIndex);

			webrtc::SdpParseError error;

			auto candidate = webrtc::CreateIceCandidate(sdpMid, sdpMLineIndex, sdp, &error);

			if (candidate == nullptr) {
				throw Exception("Create ICE candidate failed: %s [%s]", error.description.c_str(), error.line.c_str());
			}

			return std::unique_ptr<webrtc::IceCandidateInterface>(candidate);
		}

		cricket::Candidate toNativeCricket(JNIEnv * env, const JavaRef<jobject> & javaType)
		{
			return toNative(env, javaType)->candidate();
		}

		JavaRTCIceCandidateClass::JavaRTCIceCandidateClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG"RTCIceCandidate");

			ctor = GetMethod(env, cls, "<init>", "(" STRING_SIG "I" STRING_SIG STRING_SIG ")V");

			sdpMid = GetFieldID(env, cls, "sdpMid", STRING_SIG);
			sdpMLineIndex = GetFieldID(env, cls, "sdpMLineIndex", "I");
			sdp = GetFieldID(env, cls, "sdp", STRING_SIG);
			serverUrl = GetFieldID(env, cls, "serverUrl", STRING_SIG);
		}
	}
}
