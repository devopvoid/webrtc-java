/*
 * Copyright 2025 Alex Andres
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

#include "api/PortAllocatorConfig.h"
#include "JavaClasses.h"
#include "JavaObject.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	namespace PortAllocatorConfig
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::PeerConnectionInterface::PortAllocatorConfig & cfg)
		{
			const auto javaClass = JavaClasses::get<JavaPortAllocatorConfigClass>(env);

			jobject jpac = env->NewObject(javaClass->cls, javaClass->ctor);

			JavaObject obj(env, JavaLocalRef<jobject>(env, jpac));
			obj.setInt(javaClass->minPort, cfg.min_port);
			obj.setInt(javaClass->maxPort, cfg.max_port);
			obj.setInt(javaClass->flags, cfg.flags);

			return JavaLocalRef<jobject>(env, jpac);
		}

		JavaPortAllocatorConfigClass::JavaPortAllocatorConfigClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG"PortAllocatorConfig");
			ctor = GetMethod(env, cls, "<init>", "()V");
			minPort = GetFieldID(env, cls, "minPort", "I");
			maxPort = GetFieldID(env, cls, "maxPort", "I");
			flags = GetFieldID(env, cls, "flags", "I");
		}
	}
}
