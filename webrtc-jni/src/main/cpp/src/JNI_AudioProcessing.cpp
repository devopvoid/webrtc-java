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

#include "JNI_AudioProcessing.h"
#include "Exception.h"
#include "JavaArrayList.h"
#include "JavaEnums.h"
#include "JavaError.h"
#include "JavaObject.h"
#include "JavaRef.h"
#include "JavaString.h"
#include "JavaUtils.h"

#include "media/audio/AudioProcessing.h"
#include "media/audio/AudioProcessingConfig.h"
#include "media/audio/AudioProcessingStreamConfig.h"
#include "api/audio/audio_frame.h"
#include "api/scoped_refptr.h"
#include "modules/audio_processing/include/audio_processing.h"
#include "rtc_base/logging.h"

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioProcessing_applyConfig
(JNIEnv * env, jobject caller, jobject config)
{
	webrtc::AudioProcessing * apm = GetHandle<webrtc::AudioProcessing>(env, caller);
	CHECK_HANDLE(apm);

	apm->ApplyConfig(jni::AudioProcessingConfig::toNative(env, jni::JavaLocalRef<jobject>(env, config)));
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioProcessing_setStreamDelayMs
(JNIEnv * env, jobject caller, jint delayMs)
{
	webrtc::AudioProcessing * apm = GetHandle<webrtc::AudioProcessing>(env, caller);
	CHECK_HANDLE(apm);

	apm->set_stream_delay_ms(delayMs);
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_media_audio_AudioProcessing_getStreamDelayMs
(JNIEnv * env, jobject caller)
{
	webrtc::AudioProcessing * apm = GetHandle<webrtc::AudioProcessing>(env, caller);
	CHECK_HANDLEV(apm, 0);

	return apm->stream_delay_ms();
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_media_audio_AudioProcessing_processStream
(JNIEnv * env, jobject caller, jbyteArray src, jobject inputConfig, jobject outputConfig, jbyteArray dest)
{
	webrtc::AudioProcessing * apm = GetHandle<webrtc::AudioProcessing>(env, caller);
	CHECK_HANDLEV(apm, 0);

	webrtc::StreamConfig srcConfig = jni::AudioProcessingStreamConfig::toNative(env, jni::JavaLocalRef<jobject>(env, inputConfig));
	webrtc::StreamConfig dstConfig = jni::AudioProcessingStreamConfig::toNative(env, jni::JavaLocalRef<jobject>(env, outputConfig));

	jboolean isDstCopy = JNI_FALSE;

	jbyte * srcPtr = env->GetByteArrayElements(src, NULL);
	jbyte * dstPtr = env->GetByteArrayElements(dest, &isDstCopy);

	const int16_t * srcFrame = reinterpret_cast<const int16_t *>(srcPtr);
	int16_t * dstFrame = reinterpret_cast<int16_t *>(dstPtr);

	int result;

	if (srcConfig.num_channels() == 1 && dstConfig.num_channels() == 2) {
		// Up-mixing, only mono to stereo.
		// For complex channel layouts a channel mixer is required.

		const size_t srcNumSamples = srcConfig.num_samples();
		const size_t dstNumChannels = dstConfig.num_channels();
		const size_t frameSize = srcNumSamples * dstNumChannels;

		if (frameSize > webrtc::AudioFrame::kMaxDataSizeSamples) {
			return -9;
		}

		for (int i = static_cast<int>(srcNumSamples) - 1; i >= 0; i--) {
			for (size_t j = 0; j < dstNumChannels; ++j) {
				dstFrame[dstNumChannels * i + j] = srcFrame[i];
			}
		}

		srcConfig.set_num_channels(dstNumChannels);

		result = apm->ProcessStream(dstFrame, srcConfig, dstConfig, dstFrame);
	}
	else {
		// Will also down-mix if required, e.g. from stereo to mono.
		result = apm->ProcessStream(srcFrame, srcConfig, dstConfig, dstFrame);
	}

	if (isDstCopy == JNI_TRUE) {
		jsize dstLength = env->GetArrayLength(dest);

		env->SetByteArrayRegion(dest, 0, dstLength, dstPtr);
	}

	env->ReleaseByteArrayElements(src, srcPtr, JNI_ABORT);
	env->ReleaseByteArrayElements(dest, dstPtr, JNI_ABORT);

	return result;
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_media_audio_AudioProcessing_processReverseStream
(JNIEnv * env, jobject caller, jbyteArray src, jobject inputConfig, jobject outputConfig, jbyteArray dest)
{
	webrtc::AudioProcessing * apm = GetHandle<webrtc::AudioProcessing>(env, caller);
	CHECK_HANDLEV(apm, 0);

	webrtc::StreamConfig srcConfig = jni::AudioProcessingStreamConfig::toNative(env, jni::JavaLocalRef<jobject>(env, inputConfig));
	webrtc::StreamConfig dstConfig = jni::AudioProcessingStreamConfig::toNative(env, jni::JavaLocalRef<jobject>(env, outputConfig));

	jboolean isDstCopy = JNI_FALSE;

	jbyte * srcPtr = env->GetByteArrayElements(src, nullptr);
	jbyte * dstPtr = env->GetByteArrayElements(dest, &isDstCopy);

	const int16_t * srcFrame = reinterpret_cast<const int16_t *>(srcPtr);
	int16_t * dstFrame = reinterpret_cast<int16_t *>(dstPtr);

	int result;

	if (srcConfig.num_channels() == 1 && dstConfig.num_channels() == 2) {
		// Up-mixing, only mono to stereo.
		// For complex channel layouts a channel mixer is required.

		const size_t srcNumSamples = srcConfig.num_samples();
		const size_t dstNumChannels = dstConfig.num_channels();
		const size_t frameSize = srcNumSamples * dstNumChannels;

		if (frameSize > webrtc::AudioFrame::kMaxDataSizeSamples) {
			return -9;
		}

		for (int i = static_cast<int>(srcNumSamples) - 1; i >= 0; i--) {
			for (size_t j = 0; j < dstNumChannels; ++j) {
				dstFrame[dstNumChannels * i + j] = srcFrame[i];
			}
		}

		srcConfig.set_num_channels(dstNumChannels);

		result = apm->ProcessReverseStream(dstFrame, srcConfig, dstConfig, dstFrame);
	}
	else {
		// Will also down-mix if required, e.g. from stereo to mono.
		result = apm->ProcessReverseStream(srcFrame, srcConfig, dstConfig, dstFrame);
	}

	if (isDstCopy == JNI_TRUE) {
		jsize dstLength = env->GetArrayLength(dest);

		env->SetByteArrayRegion(dest, 0, dstLength, dstPtr);
	}

	env->ReleaseByteArrayElements(src, srcPtr, JNI_ABORT);
	env->ReleaseByteArrayElements(dest, dstPtr, JNI_ABORT);

	return result;
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioProcessing_dispose
(JNIEnv * env, jobject caller)
{
	webrtc::AudioProcessing * apm = GetHandle<webrtc::AudioProcessing>(env, caller);
	CHECK_HANDLE(apm);

	rtc::RefCountReleaseStatus status = apm->Release();

	if (status != rtc::RefCountReleaseStatus::kDroppedLastRef) {
		RTC_LOG(LS_WARNING) << "Native object was not deleted. A reference is still around somewhere.";
	}

	SetHandle<std::nullptr_t>(env, caller, nullptr);

	apm = nullptr;
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioProcessing_initialize
(JNIEnv * env, jobject caller)
{
	rtc::scoped_refptr<webrtc::AudioProcessing> apm = webrtc::AudioProcessingBuilder().Create();

	if (!apm) {
		env->Throw(jni::JavaError(env, "Create AudioProcessing failed"));
		return;
	}

	SetHandle(env, caller, apm.release());
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioProcessing_updateStats
(JNIEnv* env, jobject caller)
{
	webrtc::AudioProcessing * apm = GetHandle<webrtc::AudioProcessing>(env, caller);
	CHECK_HANDLE(apm);

	jni::AudioProcessing::updateStats(apm->GetStatistics(), env, jni::JavaLocalRef<jobject>(env, caller));
}