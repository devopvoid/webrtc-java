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
#include "NativeHandle.h"
#include "WebRTCContext.h"

#include "media/audio/AudioProcessing.h"
#include "media/audio/AudioProcessingConfig.h"
#include "media/audio/AudioProcessingStreamConfig.h"
#include "api/audio/audio_frame.h"
#include "api/audio/audio_processing.h"
#include "api/audio/builtin_audio_processing_builder.h"
#include "api/scoped_refptr.h"
#include "modules/audio_processing/include/audio_processing.h"
#include "rtc_base/logging.h"

#include <algorithm>
#include <memory>

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioProcessing_applyConfig
(JNIEnv * env, jobject caller, jobject config)
{
	try {
		webrtc::AudioProcessing * apm = GetHandle<webrtc::AudioProcessing>(env, caller);
		CHECK_HANDLE(apm);

		apm->ApplyConfig(jni::AudioProcessingConfig::toNative(env, jni::JavaLocalRef<jobject>(env, config)));
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
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

// Shared by processStream and processReverseStream, which differ only in the
// direction they feed the audio processing module.
static jint processFrame(JNIEnv * env, jobject caller, jbyteArray src, jobject inputConfig,
	jobject outputConfig, jbyteArray dest, bool reverse)
{
	try {
		webrtc::AudioProcessing * apm = GetHandle<webrtc::AudioProcessing>(env, caller);
		CHECK_HANDLEV(apm, 0);

		webrtc::StreamConfig srcConfig = jni::AudioProcessingStreamConfig::toNative(env, jni::JavaLocalRef<jobject>(env, inputConfig));
		webrtc::StreamConfig dstConfig = jni::AudioProcessingStreamConfig::toNative(env, jni::JavaLocalRef<jobject>(env, outputConfig));

		// Up-mixing, only mono to stereo, builds the stereo input in dest.
		// For complex channel layouts a channel mixer is required.
		const bool upMix = srcConfig.num_channels() == 1 && dstConfig.num_channels() == 2;
		const size_t srcNumSamples = srcConfig.num_samples();
		const size_t dstNumChannels = dstConfig.num_channels();
		const size_t dstNumSamples = upMix
			? std::max(srcNumSamples * dstNumChannels, dstConfig.num_samples())
			: dstConfig.num_samples();

		if (upMix && srcNumSamples * dstNumChannels > webrtc::AudioFrame::kMaxDataSizeSamples) {
			return webrtc::AudioProcessing::kBadNumberChannelsError;
		}
		if (static_cast<size_t>(env->GetArrayLength(src)) < srcNumSamples * sizeof(int16_t) ||
			static_cast<size_t>(env->GetArrayLength(dest)) < dstNumSamples * sizeof(int16_t)) {
			return webrtc::AudioProcessing::kBadDataLengthError;
		}

		auto releaseSrc = [env, src](jbyte * bytes) {
			env->ReleaseByteArrayElements(src, bytes, JNI_ABORT);
		};
		std::unique_ptr<jbyte, decltype(releaseSrc)> srcPtr(env->GetByteArrayElements(src, nullptr), releaseSrc);
		if (!srcPtr) {
			return webrtc::AudioProcessing::kUnspecifiedError;
		}

		// Mode 0 writes the samples back when the VM handed out a copy.
		auto releaseDst = [env, dest](jbyte * bytes) {
			env->ReleaseByteArrayElements(dest, bytes, 0);
		};
		std::unique_ptr<jbyte, decltype(releaseDst)> dstPtr(env->GetByteArrayElements(dest, nullptr), releaseDst);
		if (!dstPtr) {
			return webrtc::AudioProcessing::kUnspecifiedError;
		}

		const int16_t * srcFrame = reinterpret_cast<const int16_t *>(srcPtr.get());
		int16_t * dstFrame = reinterpret_cast<int16_t *>(dstPtr.get());

		if (upMix) {
			for (int i = static_cast<int>(srcNumSamples) - 1; i >= 0; i--) {
				for (size_t j = 0; j < dstNumChannels; ++j) {
					dstFrame[dstNumChannels * i + j] = srcFrame[i];
				}
			}

			srcConfig.set_num_channels(dstNumChannels);
			srcFrame = dstFrame;
		}

		// Will also down-mix if required, e.g. from stereo to mono.
		return reverse
			? apm->ProcessReverseStream(srcFrame, srcConfig, dstConfig, dstFrame)
			: apm->ProcessStream(srcFrame, srcConfig, dstConfig, dstFrame);
	}
	catch (...) {
		ThrowCxxJavaException(env);
		return webrtc::AudioProcessing::kUnspecifiedError;
	}
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_media_audio_AudioProcessing_processStream
(JNIEnv * env, jobject caller, jbyteArray src, jobject inputConfig, jobject outputConfig, jbyteArray dest)
{
	return processFrame(env, caller, src, inputConfig, outputConfig, dest, false);
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_media_audio_AudioProcessing_processReverseStream
(JNIEnv * env, jobject caller, jbyteArray src, jobject inputConfig, jobject outputConfig, jbyteArray dest)
{
	return processFrame(env, caller, src, inputConfig, outputConfig, dest, true);
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioProcessing_dispose
(JNIEnv * env, jobject caller)
{
	webrtc::AudioProcessing * apm = GetHandle<webrtc::AudioProcessing>(env, caller);
	CHECK_HANDLE(apm);

	webrtc::RefCountReleaseStatus status = apm->Release();

	if (status != webrtc::RefCountReleaseStatus::kDroppedLastRef) {
		RTC_LOG(LS_WARNING) << "Native object was not deleted. A reference is still around somewhere.";
	}

	SetHandle<std::nullptr_t>(env, caller, nullptr);

	apm = nullptr;
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioProcessing_initialize
(JNIEnv * env, jobject caller)
{
	jni::WebRTCContext * context = static_cast<jni::WebRTCContext*>(javaContext);

	webrtc::scoped_refptr<webrtc::AudioProcessing> apm = webrtc::BuiltinAudioProcessingBuilder().Build(context->webrtcEnv);

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