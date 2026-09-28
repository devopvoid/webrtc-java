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

		// Wraps the frame for Java without copying its pixels, converting
		// them to I420 first if they are in another format. The Java frame
		// holds a reference to the pixel buffer, which it gives up when it
		// is released. Returns null if the pixels cannot be converted.
		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::VideoFrame & frame);

		// Returns the native pixel buffer of a Java VideoFrameBuffer. A
		// NativeI420Buffer is shared; any other buffer is converted to I420
		// in Java and copied. Returns null if it has no readable pixels.
		webrtc::scoped_refptr<webrtc::VideoFrameBuffer> toNativeBuffer(JNIEnv * env, const JavaRef<jobject> & javaBuffer);
	}

	namespace I420Buffer
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::scoped_refptr<webrtc::I420BufferInterface> & buffer);
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
			jmethodID release;
	};

	class JavaI420BufferClass : public JavaClass
	{
		public:
			explicit JavaI420BufferClass(JNIEnv * env);

			jclass cls;
			jmethodID toI420;
			jmethodID getWidth;
			jmethodID getHeight;
			jmethodID getDataY;
			jmethodID getDataU;
			jmethodID getDataV;
			jmethodID getStrideY;
			jmethodID getStrideU;
			jmethodID getStrideV;
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