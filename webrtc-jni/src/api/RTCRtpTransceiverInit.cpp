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

#include "api/RTCRtpTransceiverInit.h"
#include "api/RTCRtpEncodingParameters.h"
#include "JavaClasses.h"
#include "JavaEnums.h"
#include "JavaIterable.h"
#include "JavaList.h"
#include "JavaObject.h"
#include "JavaString.h"
#include "JNI_WebRTC.h"

namespace jni
{
	namespace RTCRtpTransceiverInit
	{
		webrtc::RtpTransceiverInit toNative(JNIEnv * env, const JavaRef<jobject>& javaType)
		{
			const auto javaClass = JavaClasses::get<JavaRTCRtpTransceiverInitClass>(env);

			JavaObject obj(env, javaType);

			JavaLocalRef<jobject> ids = obj.getObject(javaClass->streamIds);
			JavaLocalRef<jobject> encodings = obj.getObject(javaClass->sendEncodings);

			webrtc::RtpTransceiverInit init;
			init.direction = JavaEnums::toNative<webrtc::RtpTransceiverDirection>(env, obj.getObject(javaClass->direction));
			init.stream_ids = JavaList::toStringVector(env, ids);
			init.send_encodings = JavaList::toVector(env, encodings, &RTCRtpEncodingParameters::toNative);

			return init;
		}

		JavaRTCRtpTransceiverInitClass::JavaRTCRtpTransceiverInitClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG"RTCRtpTransceiverInit");

			direction = GetFieldID(env, cls, "direction", "L" PKG "RTCRtpTransceiverDirection;");
			streamIds = GetFieldID(env, cls, "streamIds", LIST_SIG);
			sendEncodings = GetFieldID(env, cls, "sendEncodings", LIST_SIG);
		}
	}
}