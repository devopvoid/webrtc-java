/*
 * Copyright 2021 Alex Andres
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

#include "JNI_AudioConverter.h"
#include "api/audio/audio_processing.h"
#include "media/audio/AudioConverter.h"
#include "JavaUtils.h"

#include <memory>
#include <stdexcept>

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioConverter_convertInternal
(JNIEnv * env, jobject caller, jbyteArray src, jint nSrcSamples, jbyteArray dst, jint nDstSamples)
{
	jni::AudioConverter * converter = GetHandle<jni::AudioConverter>(env, caller);
	CHECK_HANDLE(converter);

	if (src == nullptr || dst == nullptr) {
		ThrowJavaException(env, "java/lang/NullPointerException", "Sample arrays must not be null");
		return;
	}

	// The counts are samples over all channels. The converter checks them against its format, this the arrays.
	const jlong bytesPerSample = sizeof(int16_t);

	if (nSrcSamples < 0 || nDstSamples < 0 ||
		env->GetArrayLength(src) < nSrcSamples * bytesPerSample || env->GetArrayLength(dst) < nDstSamples * bytesPerSample) {
		ThrowJavaException(env, "java/lang/IllegalArgumentException", "Sample arrays too short for the requested samples");
		return;
	}

	try {
		auto releaseSrc = [env, src](jbyte * bytes) {
			env->ReleaseByteArrayElements(src, bytes, JNI_ABORT);
		};
		std::unique_ptr<jbyte, decltype(releaseSrc)> srcPtr(env->GetByteArrayElements(src, nullptr), releaseSrc);
		if (!srcPtr) {
			return;
		}

		// Mode 0 writes the samples back when the VM handed out a copy.
		auto releaseDst = [env, dst](jbyte * bytes) {
			env->ReleaseByteArrayElements(dst, bytes, 0);
		};
		std::unique_ptr<jbyte, decltype(releaseDst)> dstPtr(env->GetByteArrayElements(dst, nullptr), releaseDst);
		if (!dstPtr) {
			return;
		}

		converter->convert(reinterpret_cast<const int16_t *>(srcPtr.get()), nSrcSamples, reinterpret_cast<int16_t *>(dstPtr.get()), nDstSamples);
	}
	catch (const std::invalid_argument & e) {
		ThrowJavaException(env, "java/lang/IllegalArgumentException", e.what());
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioConverter_dispose
(JNIEnv * env, jobject caller)
{
	jni::AudioConverter * converter = GetHandle<jni::AudioConverter>(env, caller);
	CHECK_HANDLE(converter);

	SetHandle<std::nullptr_t>(env, caller, nullptr);

	delete converter;
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioConverter_initialize
(JNIEnv * env, jobject caller, jint srcSampleRate, jint srcChannels, jint dstSampleRate, jint dstChannels)
{
	// 10 ms frames
	size_t srcFrames = webrtc::AudioProcessing::GetFrameSize(srcSampleRate);
	size_t dstFrames = webrtc::AudioProcessing::GetFrameSize(dstSampleRate);

	try {
		jni::AudioConverter * converter = jni::AudioConverter::create(srcFrames, srcChannels, dstFrames, dstChannels).release();

		SetHandle(env, caller, converter);
	}
	catch (const std::invalid_argument & e) {
		ThrowJavaException(env, "java/lang/IllegalArgumentException", e.what());
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}