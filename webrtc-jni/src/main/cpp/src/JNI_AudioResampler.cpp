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

#include <memory>

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
	try {
		webrtc::PushResampler<int16_t> * resampler = new webrtc::PushResampler<int16_t>();

		SetHandle(env, caller, resampler);
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioResampler_initialize
(JNIEnv* env, jobject caller, jint srcSamplesPerChannel, jint dstSamplesPerChannel, jint channels)
{
	try {
		webrtc::PushResampler<int16_t> * resampler = new webrtc::PushResampler<int16_t>(srcSamplesPerChannel, dstSamplesPerChannel, channels);

		SetHandle(env, caller, resampler);
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_media_audio_AudioResampler_resampleInternal
(JNIEnv * env, jobject caller, jbyteArray samplesIn, jint srcSamplesPerChannel, jbyteArray samplesOut, jint dstSamplesPerChannel, jint channels)
{
	try {
		webrtc::PushResampler<int16_t> * resampler = GetHandle<webrtc::PushResampler<int16_t>>(env, caller);
		CHECK_HANDLEV(resampler, -1);

		auto releaseSrc = [env, samplesIn](jbyte * bytes) {
			env->ReleaseByteArrayElements(samplesIn, bytes, JNI_ABORT);
		};
		std::unique_ptr<jbyte, decltype(releaseSrc)> srcPtr(env->GetByteArrayElements(samplesIn, nullptr), releaseSrc);
		if (!srcPtr) {
			return -1;
		}

		// Mode 0 writes the samples back when the VM handed out a copy.
		auto releaseDst = [env, samplesOut](jbyte * bytes) {
			env->ReleaseByteArrayElements(samplesOut, bytes, 0);
		};
		std::unique_ptr<jbyte, decltype(releaseDst)> dstPtr(env->GetByteArrayElements(samplesOut, nullptr), releaseDst);
		if (!dstPtr) {
			return -1;
		}

		int16_t * src16Ptr = reinterpret_cast<int16_t *>(srcPtr.get());
		int16_t * dst16Ptr = reinterpret_cast<int16_t*>(dstPtr.get());

		webrtc::InterleavedView<const int16_t> src(src16Ptr, srcSamplesPerChannel, channels);
		webrtc::InterleavedView<int16_t> dst(dst16Ptr, dstSamplesPerChannel, channels);

		resampler->Resample(src, dst);

		return static_cast<jint>(dstSamplesPerChannel);
	}
	catch (...) {
		ThrowCxxJavaException(env);
		return 0;
	}
}
