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

#include "media/video/desktop/DesktopFrame.h"
#include "JavaClasses.h"
#include "JavaDimension.h"
#include "JavaRectangle.h"
#include "JNI_WebRTC.h"

namespace jni
{
	namespace DesktopFrame
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::DesktopFrame * frame)
		{
			if (frame == nullptr) {
				return nullptr;
			}

			const webrtc::DesktopRect & rect = frame->rect();
			const webrtc::DesktopSize & size = frame->size();

			const auto javaClass = JavaClasses::get<JavaDesktopFrameClass>(env);

			jobject buffer = env->NewDirectByteBuffer(frame->data(), frame->stride() * frame->size().height());

			jobject object = env->NewObject(javaClass->cls, javaClass->ctor,
				JavaRectangle::toJava(env, rect.left(), rect.top(), rect.width(), rect.height()).get(),
				JavaDimension::toJava(env, size.width(), size.height()).get(),
				static_cast<jfloat>(frame->scale_factor()),
				static_cast<jint>(frame->stride()),
				buffer);

			return JavaLocalRef<jobject>(env, object);
		}

		JavaDesktopFrameClass::JavaDesktopFrameClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG_DESKTOP"DesktopFrame");

			ctor = GetMethod(env, cls, "<init>", "(Ljava/awt/Rectangle;Ljava/awt/Dimension;FI" BYTE_BUFFER_SIG ")V");
		}
	}
}