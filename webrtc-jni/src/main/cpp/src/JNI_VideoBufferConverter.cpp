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

#include <climits>
#include <cstdint>
#include <memory>

// The size of a frame in the layout libyuv::ConvertToI420 reads, which
// ConvertFromI420 below writes. YUY2 and UYVY rows, and NV12 and NV21 chroma
// rows, hold whole pixel pairs, so an odd width is rounded up to the next pair.
// Returns false for an empty frame, an unsupported format, or a width beyond
// the INT_MAX / 4 that ConvertToI420 accepts. With that width, no row of 4
// bytes per pixel overflows an int, and no frame size overflows 64 bits.
static bool CalcBufferSize(jint width, jint height, jint fourCC, uint64_t & size)
{
	if (width <= 0 || height <= 0 || width > INT_MAX / 4) {
		return false;
	}

	const uint64_t pixels = static_cast<uint64_t>(width) * height;
	const uint64_t pairs = (static_cast<uint64_t>(width) + 1) / 2;
	const uint64_t chromaRows = (static_cast<uint64_t>(height) + 1) / 2;

	switch (fourCC) {
		case libyuv::FOURCC_I420:
		case libyuv::FOURCC_NV12:
		case libyuv::FOURCC_NV21:
		case libyuv::FOURCC_IYUV:
		case libyuv::FOURCC_YV12:
			size = pixels + pairs * chromaRows * 2;
			return true;

		case libyuv::FOURCC_R444:
		case libyuv::FOURCC_RGBP:
		case libyuv::FOURCC_RGBO:
			size = pixels * 2;
			return true;

		case libyuv::FOURCC_YUY2:
		case libyuv::FOURCC_UYVY:
			size = pairs * 4 * height;
			return true;

		case libyuv::FOURCC_24BG:
			size = pixels * 3;
			return true;

		case libyuv::FOURCC_ARGB:
		case libyuv::FOURCC_ABGR:
		case libyuv::FOURCC_BGRA:
		case libyuv::FOURCC_RGBA:
			size = pixels * 4;
			return true;

		default:
			return false;
	}
}

// Throws and returns false unless a frame of the given size and format fits into length bytes.
static bool CheckBufferSize(JNIEnv * env, jint width, jint height, jint fourCC, uint64_t length)
{
	uint64_t requiredSize;

	if (!CalcBufferSize(width, height, fourCC, requiredSize)) {
		env->Throw(jni::JavaRuntimeException(env, "Invalid frame [width %d, height %d, format 0x%08x]",
			width, height, static_cast<unsigned>(fourCC)));
		return false;
	}
	if (length < requiredSize) {
		env->Throw(jni::JavaRuntimeException(env, "Insufficient buffer size [has %llu, need %llu]",
			static_cast<unsigned long long>(length), static_cast<unsigned long long>(requiredSize)));
		return false;
	}

	return true;
}

// libyuv::ConvertFromI420 with the strides of CalcBufferSize. On its own it
// writes YUY2 and UYVY rows width * 2 bytes apart, and NV12 and NV21 chroma
// rows width bytes apart, though each of these rows ends with a whole pair.
static int ConvertFromI420(const uint8_t * srcY, int srcStrideY, const uint8_t * srcU, int srcStrideU,
	const uint8_t * srcV, int srcStrideV, uint8_t * dst, int width, int height, uint32_t fourCC)
{
	const int alignedWidth = (width + 1) & ~1;
	const ptrdiff_t lumaSize = static_cast<ptrdiff_t>(width) * height;

	switch (fourCC) {
		case libyuv::FOURCC_YUY2:
			return libyuv::I420ToYUY2(srcY, srcStrideY, srcU, srcStrideU, srcV, srcStrideV,
				dst, alignedWidth * 2, width, height);

		case libyuv::FOURCC_UYVY:
			return libyuv::I420ToUYVY(srcY, srcStrideY, srcU, srcStrideU, srcV, srcStrideV,
				dst, alignedWidth * 2, width, height);

		case libyuv::FOURCC_NV12:
			return libyuv::I420ToNV12(srcY, srcStrideY, srcU, srcStrideU, srcV, srcStrideV,
				dst, width, dst + lumaSize, alignedWidth, width, height);

		case libyuv::FOURCC_NV21:
			return libyuv::I420ToNV21(srcY, srcStrideY, srcU, srcStrideU, srcV, srcStrideV,
				dst, width, dst + lumaSize, alignedWidth, width, height);

		default:
			return libyuv::ConvertFromI420(srcY, srcStrideY, srcU, srcStrideU, srcV, srcStrideV,
				dst, 0, width, height, fourCC);
	}
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

		if (!CheckBufferSize(env, width, height, fourCC, static_cast<uint64_t>(dstLength))) {
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

		const int conversionResult = ConvertFromI420(srcY, srcStrideY, srcU, srcStrideU,
			srcV, srcStrideV, dstPtr, width, height, static_cast<uint32_t>(fourCC));

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
		jlong bufferLength = env->GetDirectBufferCapacity(dst);

		if (!CheckBufferSize(env, width, height, fourCC, static_cast<uint64_t>(bufferLength))) {
			return;
		}

		const int conversionResult = ConvertFromI420(srcY, srcStrideY, srcU, srcStrideU,
			srcV, srcStrideV, address, width, height, static_cast<uint32_t>(fourCC));

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

		if (!CheckBufferSize(env, width, height, fourCC, static_cast<uint64_t>(srcLength))) {
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
		jlong bufferLength = env->GetDirectBufferCapacity(src);

		if (!CheckBufferSize(env, width, height, fourCC, static_cast<uint64_t>(bufferLength))) {
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