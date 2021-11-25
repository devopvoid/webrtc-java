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
#include "media/audio/AudioConverter.h"
#include "JavaUtils.h"

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioConverter_convertInternal
(JNIEnv * env, jobject caller, jbyteArray src, jint nSrcSamples, jbyteArray dst, jint nDstSamples)
{
	jni::AudioConverter * converter = GetHandle<jni::AudioConverter>(env, caller);
	CHECK_HANDLE(converter);

	jboolean isDstCopy = JNI_FALSE;

	jbyte * srcPtr = env->GetByteArrayElements(src, nullptr);
	jbyte * dstPtr = env->GetByteArrayElements(dst, &isDstCopy);

	converter->convert(reinterpret_cast<const int16_t *>(srcPtr), nSrcSamples, reinterpret_cast<int16_t *>(dstPtr), nDstSamples);

	if (isDstCopy == JNI_TRUE) {
		jsize dstLength = env->GetArrayLength(dst);

		env->SetByteArrayRegion(dst, 0, dstLength, dstPtr);
	}

	env->ReleaseByteArrayElements(src, srcPtr, JNI_ABORT);
	env->ReleaseByteArrayElements(dst, dstPtr, JNI_ABORT);
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
	size_t srcFrames = srcSampleRate / 100;
	size_t dstFrames = dstSampleRate / 100;

	jni::AudioConverter * converter = jni::AudioConverter::create(srcFrames, srcChannels, dstFrames, dstChannels).release();

	SetHandle(env, caller, converter);
}