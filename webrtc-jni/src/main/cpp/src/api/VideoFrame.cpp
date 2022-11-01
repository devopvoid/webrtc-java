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

#include "api/VideoFrame.h"
#include "JavaObject.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

#include "rtc_base/time_utils.h"

namespace jni
{
	namespace VideoFrame
	{
		webrtc::VideoFrame toNative(JNIEnv * env, const JavaRef<jobject> & javaFrame)
		{
			const auto javaClass = JavaClasses::get<JavaVideoFrameClass>(env);
			JavaObject obj(env, javaFrame);

			int rotation = obj.getInt(javaClass->rotation);
			int64_t timestamp_ns = obj.getLong(javaClass->timestampNs);

			return webrtc::VideoFrame::Builder()
				//.set_video_frame_buffer(buffer)
				//.set_timestamp_rtp(timestamp_rtp)
				.set_timestamp_ms(timestamp_ns / rtc::kNumNanosecsPerMillisec)
				.set_rotation(static_cast<webrtc::VideoRotation>(rotation))
				.build();
		}
	}

	namespace I420Buffer
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const rtc::scoped_refptr<webrtc::I420BufferInterface> & buffer)
		{
			const auto javaClass = JavaClasses::get<JavaNativeI420BufferClass>(env);
			
			jobject yBuffer = env->NewDirectByteBuffer(const_cast<uint8_t *>(buffer->DataY()), static_cast<jlong>(buffer->StrideY()) * buffer->height());
			jobject uBuffer = env->NewDirectByteBuffer(const_cast<uint8_t *>(buffer->DataU()), static_cast<jlong>(buffer->StrideU()) * buffer->ChromaHeight());
			jobject vBuffer = env->NewDirectByteBuffer(const_cast<uint8_t *>(buffer->DataV()), static_cast<jlong>(buffer->StrideV()) * buffer->ChromaHeight());

			jobject jBuffer = env->NewObject(javaClass->cls, javaClass->ctor, buffer->width(), buffer->height(),
				yBuffer, buffer->StrideY(), uBuffer, buffer->StrideU(), vBuffer, buffer->StrideV());

			SetHandle(env, jBuffer, buffer.get());

			env->DeleteLocalRef(yBuffer);
			env->DeleteLocalRef(uBuffer);
			env->DeleteLocalRef(vBuffer);

			return JavaLocalRef<jobject>(env, jBuffer);
		}
	}

	JavaVideoFrameClass::JavaVideoFrameClass(JNIEnv * env)
	{
		cls = FindClass(env, PKG_VIDEO"VideoFrame");

		ctor = GetMethod(env, cls, "<init>", "(L" PKG_VIDEO "VideoFrameBuffer;IJ)V");

		buffer = GetFieldID(env, cls, "buffer", "L" PKG_VIDEO "VideoFrameBuffer;");
		rotation = GetFieldID(env, cls, "rotation", "I");
		timestampNs = GetFieldID(env, cls, "timestampNs", "J");
	}

	JavaNativeI420BufferClass::JavaNativeI420BufferClass(JNIEnv * env)
	{
		cls = FindClass(env, PKG_VIDEO"NativeI420Buffer");

		ctor = GetMethod(env, cls, "<init>", "(II" BYTE_BUFFER_SIG "I" BYTE_BUFFER_SIG "I" BYTE_BUFFER_SIG "I)V");

		dataY = GetFieldID(env, cls, "dataY", BYTE_BUFFER_SIG);
		dataU = GetFieldID(env, cls, "dataU", BYTE_BUFFER_SIG);
		dataV = GetFieldID(env, cls, "dataV", BYTE_BUFFER_SIG);
		strideY = GetFieldID(env, cls, "strideY", "I");
		strideU = GetFieldID(env, cls, "strideU", "I");
		strideV = GetFieldID(env, cls, "strideV", "I");
		width = GetFieldID(env, cls, "width", "I");
		height = GetFieldID(env, cls, "height", "I");
	}
}
