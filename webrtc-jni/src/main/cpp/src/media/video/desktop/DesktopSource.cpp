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

#include "media/video/desktop/DesktopSource.h"
#include "JavaClasses.h"
#include "JavaObject.h"
#include "JavaString.h"
#include "JNI_WebRTC.h"

namespace jni
{
	namespace DesktopSource
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::DesktopCapturer::Source & source)
		{
			const auto javaClass = JavaClasses::get<JavaDesktopSourceClass>(env);

			jobject obj = env->NewObject(javaClass->cls, javaClass->ctor,
				JavaString::toJava(env, source.title).get(),
				static_cast<jlong>(source.id));

			return JavaLocalRef<jobject>(env, obj);
		}

		webrtc::DesktopCapturer::Source toNative(JNIEnv * env, const JavaRef<jobject> & javaType)
		{
			const auto javaClass = JavaClasses::get<JavaDesktopSourceClass>(env);

			JavaObject obj(env, javaType);

			auto source = webrtc::DesktopCapturer::Source();
			source.id = static_cast<webrtc::DesktopCapturer::SourceId>(obj.getLong(javaClass->id));
			source.title = JavaString::toNative(env, obj.getString(javaClass->title));

			return source;
		}

		JavaDesktopSourceClass::JavaDesktopSourceClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG_DESKTOP"DesktopSource");

			ctor = GetMethod(env, cls, "<init>", "(" STRING_SIG "J)V");

			id = GetFieldID(env, cls, "id", "J");
			title = GetFieldID(env, cls, "title", STRING_SIG);
		}
	}
}