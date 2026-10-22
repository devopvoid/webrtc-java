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

#include "JNI_VideoBufferConverter.h"
#include "JavaRuntimeException.h"
#include "JavaUtils.h"

#include "api/video/i420_buffer.h"

#include "libyuv/convert.h"
#include "libyuv/video_common.h"

#include <memory>

size_t CalcBufferSize(int width, int height, int fourCC) {
	size_t bufferSize = 0;

	switch (fourCC) {
		case libyuv::FOURCC_I420:
		case libyuv::FOURCC_NV12:
		case libyuv::FOURCC_NV21:
		case libyuv::FOURCC_IYUV:
		case libyuv::FOURCC_YV12:
			bufferSize = width * height + ((width + 1) >> 1) * ((height + 1) >> 1) * 2;
			break;

		case libyuv::FOURCC_R444:
		case libyuv::FOURCC_RGBP:
		case libyuv::FOURCC_RGBO:
		case libyuv::FOURCC_YUY2:
		case libyuv::FOURCC_UYVY:
			bufferSize = width * height * 2;
			break;

		case libyuv::FOURCC_24BG:
			bufferSize = width * height * 3;
			break;

		case libyuv::FOURCC_ARGB:
		case libyuv::FOURCC_ABGR:
		case libyuv::FOURCC_BGRA:
		case libyuv::FOURCC_RGBA:
			bufferSize = width * height * 4;
			break;

		default:
			break;
	}

	return bufferSize;
}

// Throws and returns false unless [offset, offset + length) lies within the array.
static bool CheckArrayWindow(JNIEnv * env, jbyteArray array, jint offset, jint length)
{
	jsize arrayLength = env->GetArrayLength(array);

	if (offset < 0 || length < 0 || offset > arrayLength - length) {
		env->Throw(jni::JavaRuntimeException(env, "Invalid array window [offset %d, length %d, array %d]",
			offset, length, arrayLength));
		return false;
	}

	return true;
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_VideoBufferConverter_I420toByteArray
(JNIEnv * env, jclass cls, jobject jSrcY, jint srcStrideY, jobject jSrcU, jint srcStrideU,
	jobject jSrcV, jint srcStrideV, jbyteArray dst, jint dstOffset, jint dstLength,
	jint width, jint height, jint fourCC)
{
	try {
		if (!CheckArrayWindow(env, dst, dstOffset, dstLength)) {
			return;
		}

		size_t requiredSize = CalcBufferSize(width, height, fourCC);

		if (static_cast<size_t>(dstLength) < requiredSize) {
			env->Throw(jni::JavaRuntimeException(env, "Insufficient buffer size [has %d, need %zd]",
				dstLength, requiredSize));
			return;
		}

		const uint8_t * srcY = static_cast<uint8_t *>(env->GetDirectBufferAddress(jSrcY));
		const uint8_t * srcU = static_cast<uint8_t *>(env->GetDirectBufferAddress(jSrcU));
		const uint8_t * srcV = static_cast<uint8_t *>(env->GetDirectBufferAddress(jSrcV));

		// Mode 0 writes the picture back when the VM handed out a copy.
		auto releaseArray = [env, dst](jbyte * bytes) {
			env->ReleaseByteArrayElements(dst, bytes, 0);
		};
		std::unique_ptr<jbyte, decltype(releaseArray)> arrayPtr(env->GetByteArrayElements(dst, nullptr), releaseArray);
		if (!arrayPtr) {
			return;
		}
		uint8_t * dstPtr = reinterpret_cast<uint8_t *>(arrayPtr.get()) + dstOffset;

		const int conversionResult = libyuv::ConvertFromI420(srcY, srcStrideY, srcU, srcStrideU,
			srcV, srcStrideV, dstPtr, 0, width, height, static_cast<uint32_t>(fourCC));

		if (conversionResult < 0) {
			env->Throw(jni::JavaRuntimeException(env, "Failed to convert buffer to I420: %d",
				conversionResult));
		}
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_VideoBufferConverter_I420toDirectBuffer
(JNIEnv * env, jclass, jobject jSrcY, jint srcStrideY, jobject jSrcU, jint srcStrideU,
	jobject jSrcV, jint srcStrideV, jobject dst, jint width, jint height, jint fourCC)
{
	const uint8_t * srcY = static_cast<uint8_t *>(env->GetDirectBufferAddress(jSrcY));
	const uint8_t * srcU = static_cast<uint8_t *>(env->GetDirectBufferAddress(jSrcU));
	const uint8_t * srcV = static_cast<uint8_t *>(env->GetDirectBufferAddress(jSrcV));

	uint8_t * address = static_cast<uint8_t *>(env->GetDirectBufferAddress(dst));

	if (address != NULL) {
		size_t bufferLength = env->GetDirectBufferCapacity(dst);
		size_t requiredSize = CalcBufferSize(width, height, fourCC);

		if (bufferLength < requiredSize) {
			env->Throw(jni::JavaRuntimeException(env, "Insufficient buffer size [has %zd, need %zd]",
				bufferLength, requiredSize));
			return;
		}

		const int conversionResult = libyuv::ConvertFromI420(srcY, srcStrideY, srcU, srcStrideU,
			srcV, srcStrideV, address, 0, width, height, static_cast<uint32_t>(fourCC));

		if (conversionResult < 0) {
			env->Throw(jni::JavaRuntimeException(env, "Failed to convert buffer to I420: %d",
				conversionResult));
		}
	}
	else {
		env->Throw(jni::JavaRuntimeException(env, "Non-direct buffer provided"));
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_VideoBufferConverter_byteArrayToI420
(JNIEnv * env, jclass cls, jbyteArray src, jint srcOffset, jint srcLength, jint width, jint height,
	jobject jDstY, jint dstStrideY, jobject jDstU, jint dstStrideU, jobject jDstV, jint dstStrideV,
	jint fourCC)
{
	try {
		if (!CheckArrayWindow(env, src, srcOffset, srcLength)) {
			return;
		}

		size_t requiredSize = CalcBufferSize(width, height, fourCC);

		if (static_cast<size_t>(srcLength) < requiredSize) {
			env->Throw(jni::JavaRuntimeException(env, "Insufficient buffer size [has %d, need %zd]",
				srcLength, requiredSize));
			return;
		}

		uint8_t * dstY = static_cast<uint8_t *>(env->GetDirectBufferAddress(jDstY));
		uint8_t * dstU = static_cast<uint8_t *>(env->GetDirectBufferAddress(jDstU));
		uint8_t * dstV = static_cast<uint8_t *>(env->GetDirectBufferAddress(jDstV));

		auto releaseArray = [env, src](jbyte * bytes) {
			env->ReleaseByteArrayElements(src, bytes, JNI_ABORT);
		};
		std::unique_ptr<jbyte, decltype(releaseArray)> arrayPtr(env->GetByteArrayElements(src, nullptr), releaseArray);
		if (!arrayPtr) {
			return;
		}
		const uint8_t * srcPtr = reinterpret_cast<uint8_t *>(arrayPtr.get()) + srcOffset;

		const int conversionResult = libyuv::ConvertToI420(
			srcPtr, srcLength,
			dstY, dstStrideY,
			dstU, dstStrideU,
			dstV, dstStrideV,
			0, 0, width, height, width, height,
			libyuv::kRotate0, static_cast<uint32_t>(fourCC));

		if (conversionResult < 0) {
			env->Throw(jni::JavaRuntimeException(env, "Failed to convert buffer to I420: %d",
				conversionResult));
		}
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_VideoBufferConverter_directBufferToI420
(JNIEnv * env, jclass, jobject src, jint width, jint height, jobject jDstY, jint dstStrideY,
	jobject jDstU, jint dstStrideU, jobject jDstV, jint dstStrideV, jint fourCC)
{
	uint8_t * dstY = static_cast<uint8_t *>(env->GetDirectBufferAddress(jDstY));
	uint8_t * dstU = static_cast<uint8_t *>(env->GetDirectBufferAddress(jDstU));
	uint8_t * dstV = static_cast<uint8_t *>(env->GetDirectBufferAddress(jDstV));

	const uint8_t * address = static_cast<uint8_t *>(env->GetDirectBufferAddress(src));

	if (address != NULL) {
		size_t bufferLength = env->GetDirectBufferCapacity(src);
		size_t requiredSize = CalcBufferSize(width, height, fourCC);

		if (bufferLength < requiredSize) {
			env->Throw(jni::JavaRuntimeException(env, "Insufficient buffer size [has %zd, need %zd]",
				bufferLength, requiredSize));
			return;
		}

		const int conversionResult = libyuv::ConvertToI420(
			address, bufferLength,
			dstY, dstStrideY,
			dstU, dstStrideU,
			dstV, dstStrideV,
			0, 0, width, height, width, height,
			libyuv::kRotate0, static_cast<uint32_t>(fourCC));

		if (conversionResult < 0) {
			env->Throw(jni::JavaRuntimeException(env, "Failed to convert buffer to I420: %d",
				conversionResult));
		}
	}
	else {
		env->Throw(jni::JavaRuntimeException(env, "Non-direct buffer provided"));
	}
}