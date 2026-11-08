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

#include "JNI_NativeApi.h"
#include "JavaBoundary.h"
#include "NativeHandle.h"
#include "media/audio/CustomAudioSource.h"
#include "media/video/CustomVideoSource.h"

#include "api/ExtensionApi.h"
#include "api/EncodedFrameTransformer.h"

namespace
{
	// Hands the reference over to the extension, which drops it again with
	// encoded_frames_release().
	jlong Retained(webrtc::scoped_refptr<jni::EncodedFrameTransformer> transformer)
	{
		return reinterpret_cast<jlong>(transformer.release());
	}
}

JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_internal_NativeApi_tableAddress
(JNIEnv * env, jclass caller)
{
	return jni::CallFromJava(env, [&]() -> jlong {
		return reinterpret_cast<jlong>(jni::GetExtensionApi());
	});
}

JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_internal_NativeApi_retainVideoSource
(JNIEnv * env, jclass caller, jobject javaSource)
{
	return jni::CallFromJava(env, [&]() -> jlong {
		if (javaSource == nullptr) {
			return 0;
		}

		jni::NativeLease<jni::CustomVideoSource> source(env, javaSource);
		CHECK_HANDLEV(source, 0);

		// AddRef while the lease still defers any concurrent dispose.
		source->AddRef();

		return reinterpret_cast<jlong>(source.get());
	});
}

JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_internal_NativeApi_retainAudioSource
(JNIEnv * env, jclass caller, jobject javaSource)
{
	return jni::CallFromJava(env, [&]() -> jlong {
		if (javaSource == nullptr) {
			return 0;
		}

		jni::NativeLease<jni::CustomAudioSource> source(env, javaSource);
		CHECK_HANDLEV(source, 0);

		source->AddRef();

		return reinterpret_cast<jlong>(source.get());
	});
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_internal_NativeApi_releaseVideoSource
(JNIEnv * env, jclass caller, jlong source)
{
	jni::CallFromJava(env, [&]() {
		jni::GetExtensionApi()->video_source_release(reinterpret_cast<void *>(source));
	});
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_internal_NativeApi_releaseAudioSource
(JNIEnv * env, jclass caller, jlong source)
{
	jni::CallFromJava(env, [&]() {
		jni::GetExtensionApi()->audio_source_release(reinterpret_cast<void *>(source));
	});
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_internal_NativeApi_version
(JNIEnv * env, jclass caller)
{
	return jni::CallFromJava(env, [&]() -> jint {
		return static_cast<jint>(jni::GetExtensionApi()->version);
	});
}

JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_internal_NativeApi_senderFrames
(JNIEnv * env, jclass caller, jlong handle)
{
	return jni::CallFromJava(env, [&]() -> jlong {
		auto sender = reinterpret_cast<webrtc::RtpSenderInterface *>(handle);

		return sender != nullptr ? Retained(jni::EncodedFrameTransformer::Of(sender)) : 0;
	});
}

JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_internal_NativeApi_receiverFrames
(JNIEnv * env, jclass caller, jlong handle)
{
	return jni::CallFromJava(env, [&]() -> jlong {
		auto receiver = reinterpret_cast<webrtc::RtpReceiverInterface *>(handle);

		return receiver != nullptr ? Retained(jni::EncodedFrameTransformer::Of(receiver)) : 0;
	});
}

JNIEXPORT jboolean JNICALL Java_dev_onvoid_webrtc_internal_NativeApi_framesSealed
(JNIEnv * env, jclass caller, jlong frames)
{
	return jni::CallFromJava(env, [&]() -> jboolean {
		auto transformer = reinterpret_cast<jni::EncodedFrameTransformer *>(frames);

		return transformer != nullptr && transformer->IsSealed() ? JNI_TRUE : JNI_FALSE;
	});
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_internal_NativeApi_releaseFrames
(JNIEnv * env, jclass caller, jlong frames)
{
	jni::CallFromJava(env, [&]() {
		jni::GetExtensionApi()->encoded_frames_release(reinterpret_cast<void *>(frames));
	});
}
