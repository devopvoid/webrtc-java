/*
 * Copyright 2019 Alex Andres
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

#include "JNI_RTCDataChannel.h"
#include "api/RTCDataChannelObserver.h"
#include "JavaEnums.h"
#include "JavaError.h"
#include "JavaRef.h"
#include "JavaString.h"
#include "JavaUtils.h"

#include "api/data_channel_interface.h"

#include <memory>

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCDataChannel_registerObserver
(JNIEnv * env, jobject caller, jobject jObserver)
{
	webrtc::DataChannelInterface * channel = GetHandle<webrtc::DataChannelInterface>(env, caller);
	CHECK_HANDLE(channel);

	channel->RegisterObserver(new jni::RTCDataChannelObserver(env, jni::JavaGlobalRef<jobject>(env, jObserver)));
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCDataChannel_unregisterObserver
(JNIEnv * env, jobject caller)
{
	webrtc::DataChannelInterface * channel = GetHandle<webrtc::DataChannelInterface>(env, caller);
	CHECK_HANDLE(channel);

	channel->UnregisterObserver();
}

JNIEXPORT jstring JNICALL Java_dev_onvoid_webrtc_RTCDataChannel_getLabel
(JNIEnv * env, jobject caller)
{
	webrtc::DataChannelInterface * channel = GetHandle<webrtc::DataChannelInterface>(env, caller);
	CHECK_HANDLEV(channel, nullptr);

	return jni::JavaString::toJava(env, channel->label()).release();
}

JNIEXPORT jboolean JNICALL Java_dev_onvoid_webrtc_RTCDataChannel_isReliable
(JNIEnv * env, jobject caller)
{
	webrtc::DataChannelInterface * channel = GetHandle<webrtc::DataChannelInterface>(env, caller);
	CHECK_HANDLEV(channel, false);

	return static_cast<jboolean>(channel->reliable());
}

JNIEXPORT jboolean JNICALL Java_dev_onvoid_webrtc_RTCDataChannel_isOrdered
(JNIEnv * env, jobject caller)
{
	webrtc::DataChannelInterface * channel = GetHandle<webrtc::DataChannelInterface>(env, caller);
	CHECK_HANDLEV(channel, false);

	return static_cast<jboolean>(channel->ordered());
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_RTCDataChannel_getMaxPacketLifeTime
(JNIEnv * env, jobject caller)
{
	webrtc::DataChannelInterface * channel = GetHandle<webrtc::DataChannelInterface>(env, caller);
	CHECK_HANDLEV(channel, 0);

	return static_cast<jint>(channel->maxRetransmitTime());
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_RTCDataChannel_getMaxRetransmits
(JNIEnv * env, jobject caller)
{
	webrtc::DataChannelInterface * channel = GetHandle<webrtc::DataChannelInterface>(env, caller);
	CHECK_HANDLEV(channel, 0);

	return static_cast<jint>(channel->maxRetransmits());
}

JNIEXPORT jstring JNICALL Java_dev_onvoid_webrtc_RTCDataChannel_getProtocol
(JNIEnv * env, jobject caller)
{
	webrtc::DataChannelInterface * channel = GetHandle<webrtc::DataChannelInterface>(env, caller);
	CHECK_HANDLEV(channel, nullptr);

	return jni::JavaString::toJava(env, channel->protocol()).release();
}

JNIEXPORT jboolean JNICALL Java_dev_onvoid_webrtc_RTCDataChannel_isNegotiated
(JNIEnv * env, jobject caller)
{
	webrtc::DataChannelInterface * channel = GetHandle<webrtc::DataChannelInterface>(env, caller);
	CHECK_HANDLEV(channel, false);

	return static_cast<jboolean>(channel->negotiated());
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_RTCDataChannel_getId
(JNIEnv * env, jobject caller)
{
	webrtc::DataChannelInterface * channel = GetHandle<webrtc::DataChannelInterface>(env, caller);
	CHECK_HANDLEV(channel, 0);

	return static_cast<jint>(channel->id());
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCDataChannel_getState
(JNIEnv * env, jobject caller)
{
	webrtc::DataChannelInterface * channel = GetHandle<webrtc::DataChannelInterface>(env, caller);
	CHECK_HANDLEV(channel, nullptr);

	return jni::JavaEnums::toJava(env, channel->state()).release();
}

JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_RTCDataChannel_getBufferedAmount
(JNIEnv * env, jobject caller)
{
	webrtc::DataChannelInterface * channel = GetHandle<webrtc::DataChannelInterface>(env, caller);
	CHECK_HANDLEV(channel, 0);

	return static_cast<jlong>(channel->buffered_amount());
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCDataChannel_close
(JNIEnv * env, jobject caller)
{
	webrtc::DataChannelInterface * channel = GetHandle<webrtc::DataChannelInterface>(env, caller);
	CHECK_HANDLE(channel);

	channel->Close();
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCDataChannel_dispose
(JNIEnv * env, jobject caller)
{
	webrtc::DataChannelInterface * channel = GetHandle<webrtc::DataChannelInterface>(env, caller);
	CHECK_HANDLE(channel);

	rtc::RefCountReleaseStatus status = channel->Release();

	if (status != rtc::RefCountReleaseStatus::kDroppedLastRef) {
		env->Throw(jni::JavaError(env, "Native object was not deleted. A reference is still around somewhere."));
	}

	SetHandle<std::nullptr_t>(env, caller, nullptr);

	channel = nullptr;
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCDataChannel_sendDirectBuffer
(JNIEnv * env, jobject caller, jobject jBuffer, jboolean isBinary)
{
	webrtc::DataChannelInterface * channel = GetHandle<webrtc::DataChannelInterface>(env, caller);
	CHECK_HANDLE(channel);

	uint8_t * address = static_cast<uint8_t *>(env->GetDirectBufferAddress(jBuffer));

	if (address != NULL) {
		jlong bufferLength = env->GetDirectBufferCapacity(jBuffer);

		rtc::CopyOnWriteBuffer data(address, static_cast<size_t>(bufferLength));

		channel->Send(webrtc::DataBuffer(data, static_cast<bool>(isBinary)));
	}
	else {
		env->Throw(jni::JavaError(env, "Non-direct buffer provided"));
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCDataChannel_sendByteArrayBuffer
(JNIEnv * env, jobject caller, jbyteArray jBufferArray, jboolean isBinary)
{
	webrtc::DataChannelInterface * channel = GetHandle<webrtc::DataChannelInterface>(env, caller);
	CHECK_HANDLE(channel);

	int8_t * arrayPtr = env->GetByteArrayElements(jBufferArray, nullptr);
	size_t arrayLength = env->GetArrayLength(jBufferArray);

	rtc::CopyOnWriteBuffer data(arrayPtr, arrayLength);

	env->ReleaseByteArrayElements(jBufferArray, arrayPtr, JNI_ABORT);
	
	try {
		channel->Send(webrtc::DataBuffer(data, static_cast<bool>(isBinary)));
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}