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

#include "JNI_StandaloneVoiceActivityDetector.h"
#include "JNI_WebRTC.h"
#include "JavaUtils.h"

#include "common_audio/vad/include/webrtc_vad.h"

#include <array>
#include <memory>

namespace
{

VadInst * GetDetector(JNIEnv * env, jobject caller)
{
	auto * detector = GetHandle<VadInst>(env, caller, "nativeHandle", PKG_INTERNAL "NativeObject");
	if (env->ExceptionCheck()) {
		return nullptr;
	}
	if (!detector) {
		ThrowJavaException(env, "java/lang/IllegalStateException", "StandaloneVoiceActivityDetector has been disposed");
	}
	return detector;
}

bool ValidMode(JNIEnv * env, jint mode)
{
	if (mode < 0 || mode > 3) {
		ThrowJavaException(env, "java/lang/IllegalArgumentException", "Invalid VAD mode");
		return false;
	}
	return true;
}

} // namespace

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_StandaloneVoiceActivityDetector_initialize
(JNIEnv * env, jobject caller, jint mode)
{
	try {
		if (!ValidMode(env, mode)) {
			return;
		}
		auto * existing = GetHandle<VadInst>(env, caller, "nativeHandle", PKG_INTERNAL "NativeObject");
		if (env->ExceptionCheck()) {
			return;
		}
		if (existing) {
			ThrowJavaException(env, "java/lang/IllegalStateException", "Detector is already initialized");
			return;
		}
		std::unique_ptr<VadInst, decltype(&WebRtcVad_Free)> detector(WebRtcVad_Create(), WebRtcVad_Free);
		if (!detector) {
			ThrowJavaException(env, "java/lang/OutOfMemoryError", "Cannot allocate VAD");
			return;
		}
		if (WebRtcVad_Init(detector.get()) != 0 || WebRtcVad_set_mode(detector.get(), mode) != 0) {
			ThrowJavaException(env, "java/lang/IllegalStateException", "Cannot initialize VAD");
			return;
		}
		SetHandle(env, caller, "nativeHandle", detector.get(), PKG_INTERNAL "NativeObject");
		if (!env->ExceptionCheck()) {
			detector.release();
		}
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT jboolean JNICALL Java_dev_onvoid_webrtc_media_audio_StandaloneVoiceActivityDetector_processInternal
(JNIEnv * env, jobject caller, jshortArray samples, jint sampleRate)
{
	try {
		VadInst * detector = GetDetector(env, caller);
		if (!detector) {
			return JNI_FALSE;
		}
		if (!samples) {
			ThrowJavaException(env, "java/lang/NullPointerException", "samples");
			return JNI_FALSE;
		}
		jsize length = env->GetArrayLength(samples);
		if (env->ExceptionCheck()) {
			return JNI_FALSE;
		}
		// Bound the copy independently of the Java-side validation.
		std::array<int16_t, 1440> frame;
		if (length <= 0 || static_cast<size_t>(length) > frame.size() ||
			WebRtcVad_ValidRateAndFrameLength(sampleRate, length) != 0) {
			ThrowJavaException(env, "java/lang/IllegalArgumentException", "Unsupported VAD rate or frame length");
			return JNI_FALSE;
		}
		env->GetShortArrayRegion(samples, 0, length, frame.data());
		if (env->ExceptionCheck()) {
			return JNI_FALSE;
		}
		int result = WebRtcVad_Process(detector, sampleRate, frame.data(), length);
		if (result < 0) {
			ThrowJavaException(env, "java/lang/IllegalStateException", "VAD processing failed");
			return JNI_FALSE;
		}
		return result == 1 ? JNI_TRUE : JNI_FALSE;
	}
	catch (...) {
		ThrowCxxJavaException(env);
		return JNI_FALSE;
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_StandaloneVoiceActivityDetector_setModeInternal
(JNIEnv * env, jobject caller, jint mode)
{
	try {
		VadInst * detector = GetDetector(env, caller);
		if (!detector || !ValidMode(env, mode)) {
			return;
		}
		if (WebRtcVad_set_mode(detector, mode) != 0) {
			ThrowJavaException(env, "java/lang/IllegalStateException", "Cannot set VAD mode");
		}
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_StandaloneVoiceActivityDetector_resetInternal
(JNIEnv * env, jobject caller, jint mode)
{
	try {
		VadInst * detector = GetDetector(env, caller);
		if (!detector || !ValidMode(env, mode)) {
			return;
		}
		if (WebRtcVad_Init(detector) != 0 || WebRtcVad_set_mode(detector, mode) != 0) {
			ThrowJavaException(env, "java/lang/IllegalStateException", "Cannot reset VAD");
		}
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_StandaloneVoiceActivityDetector_dispose
(JNIEnv * env, jobject caller)
{
	try {
		auto * detector = GetHandle<VadInst>(env, caller, "nativeHandle", PKG_INTERNAL "NativeObject");
		if (env->ExceptionCheck()) {
			return;
		}
		SetHandle<std::nullptr_t>(env, caller, "nativeHandle", nullptr, PKG_INTERNAL "NativeObject");
		if (!env->ExceptionCheck()) {
			WebRtcVad_Free(detector);
		}
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}
