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
#include "JavaRuntimeException.h"
#include "JavaWrappedException.h"
#include "JNI_WebRTC.h"

#include "api/video/i420_buffer.h"
#include "rtc_base/time_utils.h"

#include <climits>
#include <cstdint>

namespace jni
{
	namespace
	{
		// Throws unless the plane holds every byte a copy reads: the last row ends at the width.
		void CheckPlane(JNIEnv * env, const char * name, const JavaRef<jobject> & data, jint stride, jint width, jint height)
		{
			if (stride < width) {
				throw JavaWrappedException(JavaLocalRef<jthrowable>(env,
					JavaRuntimeException(env, "Stride %d of the %s plane is less than its width %d", stride, name, width)));
			}

			// Neither factor exceeds an int, so the product cannot overflow.
			const int64_t required = static_cast<int64_t>(stride) * (height - 1) + width;
			const jlong capacity = env->GetDirectBufferCapacity(data);

			if (capacity < required) {
				throw JavaWrappedException(JavaLocalRef<jthrowable>(env,
					JavaRuntimeException(env, "The %s plane holds %lld bytes, but a %dx%d plane with stride %d needs %lld",
						name, static_cast<long long>(capacity), width, height, stride, static_cast<long long>(required))));
			}
		}
	}

	namespace VideoFrame
	{
		webrtc::VideoFrame toNative(JNIEnv * env, const JavaRef<jobject> & javaFrame)
		{
			const auto javaClass = JavaClasses::get<JavaVideoFrameClass>(env);
			JavaObject obj(env, javaFrame);

			int rotation = obj.getInt(javaClass->rotation);
			int64_t timestamp_ns = obj.getLong(javaClass->timestampNs);
			JavaLocalRef<jobject> jBuffer = obj.getObject(javaClass->buffer);

			webrtc::scoped_refptr<webrtc::VideoFrameBuffer> buffer = toNativeBuffer(env, jBuffer);

			if (buffer == nullptr) {
				throw JavaWrappedException(JavaLocalRef<jthrowable>(env,
					JavaRuntimeException(env, "The buffer of the VideoFrame cannot be read")));
			}

			return webrtc::VideoFrame::Builder()
				.set_video_frame_buffer(buffer)
				//.set_timestamp_rtp(timestamp_rtp)
				.set_timestamp_ms(timestamp_ns / webrtc::kNumNanosecsPerMillisec)
				.set_rotation(static_cast<webrtc::VideoRotation>(rotation))
				.build();
		}

		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::VideoFrame & frame)
		{
			webrtc::scoped_refptr<webrtc::I420BufferInterface> i420Buffer = frame.video_frame_buffer()->ToI420();

			if (i420Buffer == nullptr) {
				return JavaLocalRef<jobject>(env, nullptr);
			}

			const auto javaClass = JavaClasses::get<JavaVideoFrameClass>(env);

			JavaLocalRef<jobject> jBuffer = I420Buffer::toJava(env, i420Buffer);

			// The reference the Java buffer gives up when it is released.
			i420Buffer->AddRef();

			jobject jFrame = env->NewObject(javaClass->cls, javaClass->ctor, jBuffer.get(),
				static_cast<jint>(frame.rotation()),
				static_cast<jlong>(frame.timestamp_us() * webrtc::kNumNanosecsPerMicrosec));

			if (jFrame == nullptr) {
				// The Java buffer never got to a frame that would release it.
				i420Buffer->Release();
				ExceptionCheck(env);
			}

			return JavaLocalRef<jobject>(env, jFrame);
		}

		webrtc::scoped_refptr<webrtc::VideoFrameBuffer> toNativeBuffer(JNIEnv * env, const JavaRef<jobject> & javaBuffer)
		{
			if (javaBuffer.get() == nullptr) {
				return nullptr;
			}

			const auto javaClass = JavaClasses::get<JavaI420BufferClass>(env);

			// A NativeI420Buffer returns itself, and so may any other buffer that keeps its pixels in one.
			JavaLocalRef<jobject> i420(env, env->CallObjectMethod(javaBuffer, javaClass->toI420));
			ExceptionCheck(env);

			if (i420.get() == nullptr) {
				return nullptr;
			}

			const auto nativeClass = JavaClasses::get<JavaNativeI420BufferClass>(env);

			if (env->IsInstanceOf(i420, nativeClass->cls)) {
				webrtc::I420BufferInterface * buffer = GetHandle<webrtc::I420BufferInterface>(env, i420);

				// Released for good, so there is nothing to read.
				if (buffer == nullptr) {
					return nullptr;
				}

				return webrtc::scoped_refptr<webrtc::VideoFrameBuffer>(buffer);
			}

			const jint width = env->CallIntMethod(i420, javaClass->getWidth);
			const jint height = env->CallIntMethod(i420, javaClass->getHeight);
			const jint strideY = env->CallIntMethod(i420, javaClass->getStrideY);
			const jint strideU = env->CallIntMethod(i420, javaClass->getStrideU);
			const jint strideV = env->CallIntMethod(i420, javaClass->getStrideV);
			ExceptionCheck(env);

			JavaLocalRef<jobject> dataY(env, env->CallObjectMethod(i420, javaClass->getDataY));
			JavaLocalRef<jobject> dataU(env, env->CallObjectMethod(i420, javaClass->getDataU));
			JavaLocalRef<jobject> dataV(env, env->CallObjectMethod(i420, javaClass->getDataV));
			ExceptionCheck(env);

			if (dataY.get() == nullptr || dataU.get() == nullptr || dataV.get() == nullptr) {
				return nullptr;
			}

			// Heap buffers have no address, and only direct ones can be read.
			const uint8_t * y = static_cast<const uint8_t *>(env->GetDirectBufferAddress(dataY));
			const uint8_t * u = static_cast<const uint8_t *>(env->GetDirectBufferAddress(dataU));
			const uint8_t * v = static_cast<const uint8_t *>(env->GetDirectBufferAddress(dataV));

			if (y == nullptr || u == nullptr || v == nullptr || width <= 0 || height <= 0) {
				return nullptr;
			}

			const jint chromaWidth = static_cast<jint>((width + 1LL) / 2);
			const jint chromaHeight = static_cast<jint>((height + 1LL) / 2);

			// WebRTC aborts the process on a copy larger than it can address with an int.
			if (static_cast<int64_t>(width) * height + 2 * static_cast<int64_t>(chromaWidth) * chromaHeight > INT_MAX) {
				throw JavaWrappedException(JavaLocalRef<jthrowable>(env,
					JavaRuntimeException(env, "Buffer size %dx%d is too large", width, height)));
			}

			CheckPlane(env, "Y", dataY, strideY, width, height);
			CheckPlane(env, "U", dataU, strideU, chromaWidth, chromaHeight);
			CheckPlane(env, "V", dataV, strideV, chromaWidth, chromaHeight);

			return webrtc::I420Buffer::Copy(width, height, y, strideY, u, strideU, v, strideV);
		}
	}

	namespace I420Buffer
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const webrtc::scoped_refptr<webrtc::I420BufferInterface> & buffer)
		{
			const auto javaClass = JavaClasses::get<JavaNativeI420BufferClass>(env);

			// The last row ends at the width: a cropped buffer has no padding behind it that belongs to it.
			auto planeSize = [](int stride, int width, int height) {
				return static_cast<jlong>(stride) * (height - 1) + width;
			};

			jobject yBuffer = env->NewDirectByteBuffer(const_cast<uint8_t *>(buffer->DataY()), planeSize(buffer->StrideY(), buffer->width(), buffer->height()));
			jobject uBuffer = env->NewDirectByteBuffer(const_cast<uint8_t *>(buffer->DataU()), planeSize(buffer->StrideU(), buffer->ChromaWidth(), buffer->ChromaHeight()));
			jobject vBuffer = env->NewDirectByteBuffer(const_cast<uint8_t *>(buffer->DataV()), planeSize(buffer->StrideV(), buffer->ChromaWidth(), buffer->ChromaHeight()));

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
		release = GetMethod(env, cls, "release", "()V");
	}

	JavaI420BufferClass::JavaI420BufferClass(JNIEnv * env)
	{
		cls = FindClass(env, PKG_VIDEO"I420Buffer");

		// Called on buffers of any kind, so it is looked up on their type.
		jclass bufferClass = FindClass(env, PKG_VIDEO"VideoFrameBuffer");

		toI420 = GetMethod(env, bufferClass, "toI420", "()L" PKG_VIDEO "I420Buffer;");
		getWidth = GetMethod(env, cls, "getWidth", "()I");
		getHeight = GetMethod(env, cls, "getHeight", "()I");
		getDataY = GetMethod(env, cls, "getDataY", "()" BYTE_BUFFER_SIG);
		getDataU = GetMethod(env, cls, "getDataU", "()" BYTE_BUFFER_SIG);
		getDataV = GetMethod(env, cls, "getDataV", "()" BYTE_BUFFER_SIG);
		getStrideY = GetMethod(env, cls, "getStrideY", "()I");
		getStrideU = GetMethod(env, cls, "getStrideU", "()I");
		getStrideV = GetMethod(env, cls, "getStrideV", "()I");
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
