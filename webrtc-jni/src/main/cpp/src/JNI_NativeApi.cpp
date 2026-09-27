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
	return reinterpret_cast<jlong>(jni::GetExtensionApi());
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_internal_NativeApi_version
(JNIEnv * env, jclass caller)
{
	return static_cast<jint>(jni::GetExtensionApi()->version);
}

JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_internal_NativeApi_senderFrames
(JNIEnv * env, jclass caller, jlong handle)
{
	auto sender = reinterpret_cast<webrtc::RtpSenderInterface *>(handle);

	return sender != nullptr ? Retained(jni::EncodedFrameTransformer::Of(sender)) : 0;
}

JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_internal_NativeApi_receiverFrames
(JNIEnv * env, jclass caller, jlong handle)
{
	auto receiver = reinterpret_cast<webrtc::RtpReceiverInterface *>(handle);

	return receiver != nullptr ? Retained(jni::EncodedFrameTransformer::Of(receiver)) : 0;
}
