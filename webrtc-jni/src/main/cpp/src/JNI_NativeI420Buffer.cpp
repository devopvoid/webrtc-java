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

#include "JNI_NativeI420Buffer.h"
#include "api/VideoFrame.h"
#include "JavaRuntimeException.h"

#include "api/video/i420_buffer.h"
#include "libyuv/scale.h"

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_media_video_NativeI420Buffer_allocate
(JNIEnv * env, jclass caller, jint width, jint height)
{
	rtc::scoped_refptr<webrtc::I420BufferInterface> i420Buffer = webrtc::I420Buffer::Create(width, height);

	jni::JavaLocalRef<jobject> jBuffer = jni::I420Buffer::toJava(env, i420Buffer);

	i420Buffer->AddRef();

	return jBuffer.release();
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_NativeI420Buffer_cropAndScale
(JNIEnv * env, jclass caller, jobject jSrcY, jint srcStrideY, jobject jSrcU, jint srcStrideU,
	jobject jSrcV, jint srcStrideV, jint cropX, jint cropY, jint cropW, jint cropH,
	jobject jDstY, jint dstStrideY, jobject jDstU, jint dstStrideU, jobject jDstV, jint dstStrideV,
	jint scaleW, jint scaleH)
{
	const uint8_t * src_y = static_cast<uint8_t *>(env->GetDirectBufferAddress(jSrcY));
	const uint8_t * src_u = static_cast<uint8_t *>(env->GetDirectBufferAddress(jSrcU));
	const uint8_t * src_v = static_cast<uint8_t *>(env->GetDirectBufferAddress(jSrcV));
	uint8_t * dst_y = static_cast<uint8_t *>(env->GetDirectBufferAddress(jDstY));
	uint8_t * dst_u = static_cast<uint8_t *>(env->GetDirectBufferAddress(jDstU));
	uint8_t * dst_v = static_cast<uint8_t *>(env->GetDirectBufferAddress(jDstV));

	// Perform cropping using pointer arithmetic.
	src_y += cropX + cropY * srcStrideY;
	src_u += cropX / 2 + cropY / 2 * srcStrideU;
	src_v += cropX / 2 + cropY / 2 * srcStrideV;

	bool ret = libyuv::I420Scale(
		src_y, srcStrideY, src_u, srcStrideU, src_v, srcStrideV, cropW,
		cropH, dst_y, dstStrideY, dst_u, dstStrideU, dst_v,
		dstStrideV, scaleW, scaleH, libyuv::kFilterBox);

	if (ret != 0) {
		env->Throw(jni::JavaRuntimeException(env, "I420Buffer crop and scale failed"));
	}
}