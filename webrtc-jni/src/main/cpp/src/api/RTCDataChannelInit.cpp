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

#include "api/RTCDataChannelInit.h"
#include "JavaClasses.h"
#include "JavaObject.h"
#include "JavaString.h"
#include "JNI_WebRTC.h"

namespace jni
{
	namespace RTCDataChannelInit
	{
		webrtc::DataChannelInit toNative(JNIEnv * env, const JavaRef<jobject> & javaType)
		{
			const auto javaClass = JavaClasses::get<JavaRTCDataChannelInitClass>(env);

			JavaObject obj(env, javaType);

			webrtc::DataChannelInit init;
			init.ordered = obj.getBoolean(javaClass->ordered);
			init.negotiated = obj.getBoolean(javaClass->negotiated);
			init.maxRetransmitTime = obj.getInt(javaClass->maxPacketLifeTime);
			init.maxRetransmits = obj.getInt(javaClass->maxRetransmits);
			init.id = obj.getInt(javaClass->id);
			init.protocol = JavaString::toNative(env, obj.getString(javaClass->protocol));

			return init;
		}

		JavaRTCDataChannelInitClass::JavaRTCDataChannelInitClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG"RTCDataChannelInit");

			ordered = GetFieldID(env, cls, "ordered", "Z");
			negotiated = GetFieldID(env, cls, "negotiated", "Z");
			maxPacketLifeTime = GetFieldID(env, cls, "maxPacketLifeTime", "I");
			maxRetransmits = GetFieldID(env, cls, "maxRetransmits", "I");
			id = GetFieldID(env, cls, "id", "I");
			protocol = GetFieldID(env, cls, "protocol", STRING_SIG);
			priority = GetFieldID(env, cls, "priority", "L" PKG "RTCPriorityType;");
		}
	}
}