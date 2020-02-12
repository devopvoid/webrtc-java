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

#ifndef JNI_WEBRTC_API_VIDEO_FRAME_H_
#define JNI_WEBRTC_API_VIDEO_FRAME_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include "api/video/video_frame.h"
#include "api/video/video_frame_buffer.h"

#include <jni.h>

namespace jni
{
	namespace VideoFrame
	{
		webrtc::VideoFrame toNative(JNIEnv * env, const JavaRef<jobject> & javaFrame);
	}

	namespace I420Buffer
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const rtc::scoped_refptr<webrtc::I420BufferInterface> & buffer);
	}

	class JavaVideoFrameClass : public JavaClass
	{
		public:
			explicit JavaVideoFrameClass(JNIEnv * env);

			jclass cls;
			jmethodID ctor;
			jfieldID buffer;
			jfieldID rotation;
			jfieldID timestampNs;
	};

	class JavaNativeI420BufferClass : public JavaClass
	{
		public:
			explicit JavaNativeI420BufferClass(JNIEnv * env);

			jclass cls;
			jmethodID ctor;
			jfieldID dataY;
			jfieldID dataU;
			jfieldID dataV;
			jfieldID strideY;
			jfieldID strideU;
			jfieldID strideV;
			jfieldID width;
			jfieldID height;
	};
}

#endif