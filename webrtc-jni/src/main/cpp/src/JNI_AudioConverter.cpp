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

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioConverter_convertInternal
(JNIEnv * env, jobject caller, jbyteArray src, jint nSrcSamples, jbyteArray dst, jint nDstSamples)
{
	try {
		jni::AudioConverter * converter = GetHandle<jni::AudioConverter>(env, caller);
		CHECK_HANDLE(converter);

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

	jni::AudioConverter * converter = jni::AudioConverter::create(srcFrames, srcChannels, dstFrames, dstChannels).release();

	SetHandle(env, caller, converter);
}