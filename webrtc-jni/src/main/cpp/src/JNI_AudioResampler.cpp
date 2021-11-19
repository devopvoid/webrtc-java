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

#include "JNI_AudioResampler.h"
#include "Exception.h"
#include "JavaObject.h"
#include "JavaRef.h"
#include "JavaUtils.h"

#include "common_audio/resampler/include/push_resampler.h"

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioResampler_dispose
(JNIEnv * env, jobject caller)
{
	webrtc::PushResampler<int16_t> * resampler = GetHandle<webrtc::PushResampler<int16_t>>(env, caller);
	CHECK_HANDLE(resampler);

	delete resampler;

	SetHandle<std::nullptr_t>(env, caller, nullptr);
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioResampler_initialize
(JNIEnv * env, jobject caller)
{
	webrtc::PushResampler<int16_t> * resampler = new webrtc::PushResampler<int16_t>();

	SetHandle(env, caller, resampler);
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioResampler_resetInternal
(JNIEnv* env, jobject caller, jint sourceRate, jint targetRate, jint channels)
{
	webrtc::PushResampler<int16_t> * resampler = GetHandle<webrtc::PushResampler<int16_t>>(env, caller);
	CHECK_HANDLE(resampler);

	resampler->InitializeIfNeeded(sourceRate, targetRate, channels);
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_media_audio_AudioResampler_resampleInternal
(JNIEnv * env, jobject caller, jbyteArray samplesIn, jint nSamplesIn, jbyteArray samplesOut, jint maxSamplesOut)
{
	webrtc::PushResampler<int16_t> * resampler = GetHandle<webrtc::PushResampler<int16_t>>(env, caller);
	CHECK_HANDLEV(resampler, -1);

	jboolean isDstCopy = JNI_FALSE;

	jbyte * srcPtr = env->GetByteArrayElements(samplesIn, nullptr);
	jbyte * dstPtr = env->GetByteArrayElements(samplesOut, &isDstCopy);

	size_t result = resampler->Resample(reinterpret_cast<int16_t *>(srcPtr), nSamplesIn, reinterpret_cast<int16_t *>(dstPtr), maxSamplesOut);

	if (isDstCopy == JNI_TRUE) {
		jsize dstLength = env->GetArrayLength(samplesOut);

		env->SetByteArrayRegion(samplesOut, 0, dstLength, dstPtr);
	}

	env->ReleaseByteArrayElements(samplesIn, srcPtr, JNI_ABORT);
	env->ReleaseByteArrayElements(samplesOut, dstPtr, JNI_ABORT);

	return static_cast<jint>(result);
}
