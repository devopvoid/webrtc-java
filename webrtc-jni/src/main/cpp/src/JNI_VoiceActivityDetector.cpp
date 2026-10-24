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

#include "JNI_VoiceActivityDetector.h"
#include "JavaUtils.h"
#include "NativeHandle.h"

#include "modules/audio_processing/vad/voice_activity_detector.h"

#include <array>
#include <memory>

namespace
{

webrtc::VoiceActivityDetector * GetDetector(JNIEnv * env, jobject caller)
{
	auto * detector = GetHandle<webrtc::VoiceActivityDetector>(env, caller);
	if (env->ExceptionCheck()) {
		return nullptr;
	}
	if (!detector) {
		ThrowJavaException(env, "java/lang/IllegalStateException", "VoiceActivityDetector has been disposed");
	}
	return detector;
}

} // namespace

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_VoiceActivityDetector_initialize
(JNIEnv * env, jobject caller)
{
	try {
		auto * existing = GetHandle<webrtc::VoiceActivityDetector>(env, caller);
		if (env->ExceptionCheck()) {
			return;
		}
		if (existing) {
			ThrowJavaException(env, "java/lang/IllegalStateException", "Detector is already initialized");
			return;
		}
		// Keep WebRTC's native allocation-failure policy. Its constructors are
		// built without exception cleanup; JNI cannot promise OOM recovery here.
		auto detector = std::make_unique<webrtc::VoiceActivityDetector>();
		SetHandle(env, caller, detector.get());
		if (!env->ExceptionCheck()) {
			detector.release();
		}
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_VoiceActivityDetector_process
(JNIEnv * env, jobject caller, jbyteArray audio, jint samplesPerChannel, jint sampleRate)
{
	try {
		auto * detector = GetDetector(env, caller);
		if (!detector) {
			return;
		}
		if (!audio) {
			ThrowJavaException(env, "java/lang/NullPointerException", "audio");
			return;
		}
		// Validate before calling upstream code: invalid formats can trigger RTC_CHECK
		// rather than returning an error. The copy must also fit the aligned buffer.
		if ((sampleRate != 8000 && sampleRate != 16000 && sampleRate != 32000 && sampleRate != 48000) ||
			samplesPerChannel != sampleRate / 100) {
			ThrowJavaException(env, "java/lang/IllegalArgumentException", "Expected 10 ms mono PCM at 8, 16, 32 or 48 kHz");
			return;
		}
		jsize length = env->GetArrayLength(audio);
		if (env->ExceptionCheck()) {
			return;
		}
		if (length < samplesPerChannel * 2) {
			ThrowJavaException(env, "java/lang/IllegalArgumentException", "Insufficient PCM input buffer");
			return;
		}
		std::array<int16_t, 480> frame;
		env->GetByteArrayRegion(audio, 0, samplesPerChannel * 2, reinterpret_cast<jbyte*>(frame.data()));
		if (env->ExceptionCheck()) {
			return;
		}
		detector->ProcessChunk(frame.data(), samplesPerChannel, sampleRate);
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT jfloat JNICALL Java_dev_onvoid_webrtc_media_audio_VoiceActivityDetector_getLastVoiceProbability
(JNIEnv * env, jobject caller)
{
	try {
		auto * detector = GetDetector(env, caller);
		return detector ? detector->last_voice_probability() : 0;
	}
	catch (...) {
		ThrowCxxJavaException(env);
		return 0;
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_VoiceActivityDetector_dispose
(JNIEnv * env, jobject caller)
{
	try {
		auto * detector = GetHandle<webrtc::VoiceActivityDetector>(env, caller);
		if (env->ExceptionCheck()) {
			return;
		}
		SetHandle<std::nullptr_t>(env, caller, nullptr);
		if (!env->ExceptionCheck()) {
			delete detector;
		}
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}
