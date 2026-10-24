/*
 * Copyright 2026 Alex Andres
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

#include "JNI_RTCEncodedAudioFrameInjector.h"
#include "api/EncodedFrameInjectorObserver.h"
#include "JavaError.h"
#include "JavaUtils.h"
#include "NativeHandle.h"

#include "api/encoded_audio_frame_injector_interface.h"
#include "api/frame_transformer_factory.h"

#include <memory>
#include <optional>
#include <span>

static void injectFrame(webrtc::EncodedAudioFrameInjectorInterface * injector,
	std::span<const uint8_t> data, jint payloadType, jlong rtpTimestamp,
	jint audioLevel, jboolean hasAudioLevel, jlong captureTimeMs, jboolean hasCaptureTime)
{
	std::optional<uint8_t> level;
	std::optional<uint64_t> captureTime;

	if (hasAudioLevel) {
		level = static_cast<uint8_t>(audioLevel);
	}
	if (hasCaptureTime) {
		captureTime = static_cast<uint64_t>(captureTimeMs);
	}

	// The injector sends on the sender's SSRC, and takes neither the MIME type
	// nor the sequence number from the frame.
	auto frame = webrtc::CreateOutgoingAudioFrame(
		webrtc::TransformableAudioFrameInterface::FrameType::kAudioFrameSpeech,
		webrtc::PayloadType(payloadType), static_cast<uint32_t>(rtpTimestamp), data.data(), data.size(),
		captureTime, 0, {}, "", std::nullopt, level);

	injector->InjectFrame(std::move(frame));
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCEncodedAudioFrameInjector_injectDirectBuffer
(JNIEnv * env, jobject caller, jobject jBuffer, jint position, jint length, jint payloadType,
	jlong rtpTimestamp, jint audioLevel, jboolean hasAudioLevel, jlong captureTimeMs, jboolean hasCaptureTime)
{
	webrtc::EncodedAudioFrameInjectorInterface * injector = GetHandle<webrtc::EncodedAudioFrameInjectorInterface>(env, caller);
	CHECK_HANDLE(injector);

	try {
		const uint8_t * address = static_cast<const uint8_t *>(env->GetDirectBufferAddress(jBuffer));

		if (address == nullptr) {
			env->Throw(jni::JavaError(env, "Non-direct buffer provided"));
			return;
		}

		jlong capacity = env->GetDirectBufferCapacity(jBuffer);

		if (position < 0 || length < 0 || static_cast<jlong>(position) + length > capacity) {
			env->Throw(jni::JavaError(env, "Buffer position/length out of bounds"));
			return;
		}

		// The frame copies the data, so the caller may reuse the buffer.
		injectFrame(injector, std::span<const uint8_t>(address + position, static_cast<size_t>(length)),
			payloadType, rtpTimestamp, audioLevel, hasAudioLevel, captureTimeMs, hasCaptureTime);
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCEncodedAudioFrameInjector_injectByteArray
(JNIEnv * env, jobject caller, jbyteArray jData, jint payloadType, jlong rtpTimestamp,
	jint audioLevel, jboolean hasAudioLevel, jlong captureTimeMs, jboolean hasCaptureTime)
{
	webrtc::EncodedAudioFrameInjectorInterface * injector = GetHandle<webrtc::EncodedAudioFrameInjectorInterface>(env, caller);
	CHECK_HANDLE(injector);

	try {
		auto releaseArray = [env, jData](jbyte * bytes) {
			env->ReleaseByteArrayElements(jData, bytes, JNI_ABORT);
		};
		std::unique_ptr<jbyte, decltype(releaseArray)> bytes(env->GetByteArrayElements(jData, nullptr), releaseArray);

		if (!bytes) {
			return;
		}

		auto data = std::span<const uint8_t>(reinterpret_cast<const uint8_t *>(bytes.get()),
			static_cast<size_t>(env->GetArrayLength(jData)));

		injectFrame(injector, data, payloadType, rtpTimestamp, audioLevel, hasAudioLevel, captureTimeMs, hasCaptureTime);
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCEncodedAudioFrameInjector_dispose
(JNIEnv * env, jobject caller)
{
	webrtc::EncodedAudioFrameInjectorInterface * injector = GetHandle<webrtc::EncodedAudioFrameInjectorInterface>(env, caller);
	CHECK_HANDLE(injector);

	// The sender keeps the injector and its callbacks; only the Java observer
	// stops hearing from them here.
	ClearNativeObserver<jni::EncodedFrameInjectorObserver>(env, caller, "observerHandle", PKG "RTCEncodedAudioFrameInjector");

	injector->Release();

	SetHandle<std::nullptr_t>(env, caller, nullptr);
}
