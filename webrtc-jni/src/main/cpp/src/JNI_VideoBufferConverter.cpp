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

#include "libyuv/convert_from.h"
#include "libyuv/video_common.h"

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

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_VideoBufferConverter_I420toByteArray
(JNIEnv * env, jclass cls, jobject jSrcY, jint srcStrideY, jobject jSrcU, jint srcStrideU,
	jobject jSrcV, jint srcStrideV, jbyteArray dst, jint width, jint height, jint fourCC)
{
	jsize arrayLength = env->GetArrayLength(dst);
	size_t requiredSize = CalcBufferSize(width, height, fourCC);

	if (arrayLength < requiredSize) {
		env->Throw(jni::JavaRuntimeException(env, "Insufficient buffer size [has %d, need %zd]",
			arrayLength, requiredSize));
		return;
	}

	const uint8_t * srcY = static_cast<uint8_t *>(env->GetDirectBufferAddress(jSrcY));
	const uint8_t * srcU = static_cast<uint8_t *>(env->GetDirectBufferAddress(jSrcU));
	const uint8_t * srcV = static_cast<uint8_t *>(env->GetDirectBufferAddress(jSrcV));

	jbyte * arrayPtr = env->GetByteArrayElements(dst, nullptr);
	uint8_t * dstPtr = reinterpret_cast<uint8_t *>(arrayPtr);

	libyuv::ConvertFromI420(srcY, srcStrideY, srcU, srcStrideU, srcV, srcStrideV,
		dstPtr, 0, width, height, static_cast<uint32_t>(fourCC));

	env->SetByteArrayRegion(dst, 0, arrayLength, arrayPtr);
	env->ReleaseByteArrayElements(dst, arrayPtr, JNI_ABORT);
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

		libyuv::ConvertFromI420(srcY, srcStrideY, srcU, srcStrideU, srcV, srcStrideV,
			address, 0, width, height, static_cast<uint32_t>(fourCC));
	}
	else {
		env->Throw(jni::JavaRuntimeException(env, "Non-direct buffer provided"));
	}
}