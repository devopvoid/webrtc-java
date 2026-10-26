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

#include "JNI_RTCDtmfSender.h"
#include "api/RegisteredObservers.h"
#include "api/RTCDtmfSenderObserver.h"
#include "JavaString.h"
#include "JavaUtils.h"

#include "api/dtmf_sender_interface.h"

namespace
{
	// Unregisters and frees the observer registered through the Java object.
	// WebRTC is told only while that observer is still the registered one:
	// another Java object for the same DTMF sender may have registered its
	// own since, which stays.
	void UnregisterOwnObserver(JNIEnv * env, jobject caller, webrtc::DtmfSenderInterface * sender)
	{
		auto observer = GetHandle<jni::RTCDtmfSenderObserver>(env, caller, "observerHandle");

		if (jni::RegisteredObservers::Remove(sender, observer)) {
			sender->UnregisterObserver();
		}

		ClearNativeObserver<jni::RTCDtmfSenderObserver>(env, caller, "observerHandle");
	}
}

JNIEXPORT jboolean JNICALL Java_dev_onvoid_webrtc_RTCDtmfSender_canInsertDtmf
(JNIEnv * env, jobject caller)
{
	webrtc::DtmfSenderInterface * sender = GetHandle<webrtc::DtmfSenderInterface>(env, caller);
	CHECK_HANDLEV(sender, false);

	return static_cast<jboolean>(sender->CanInsertDtmf());
}

JNIEXPORT jboolean JNICALL Java_dev_onvoid_webrtc_RTCDtmfSender_insertDtmf
(JNIEnv * env, jobject caller, jstring jTones, jint duration, jint interToneGap)
{
	try {
		webrtc::DtmfSenderInterface * sender = GetHandle<webrtc::DtmfSenderInterface>(env, caller);
		CHECK_HANDLEV(sender, false);

		std::string tones = jni::JavaString::toNative(env, jni::JavaLocalRef<jstring>(env, jTones));

		return static_cast<jboolean>(sender->InsertDtmf(tones, static_cast<int>(duration), static_cast<int>(interToneGap)));
	}
	catch (...) {
		ThrowCxxJavaException(env);
		return JNI_FALSE;
	}
}

JNIEXPORT jstring JNICALL Java_dev_onvoid_webrtc_RTCDtmfSender_tones
(JNIEnv * env, jobject caller)
{
	try {
		webrtc::DtmfSenderInterface * sender = GetHandle<webrtc::DtmfSenderInterface>(env, caller);
		CHECK_HANDLEV(sender, nullptr);

		return jni::JavaString::toJava(env, sender->tones()).release();
	}
	catch (...) {
		ThrowCxxJavaException(env);
		return nullptr;
	}
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_RTCDtmfSender_duration
(JNIEnv * env, jobject caller)
{
	webrtc::DtmfSenderInterface * sender = GetHandle<webrtc::DtmfSenderInterface>(env, caller);
	CHECK_HANDLEV(sender, 0);

	return static_cast<jint>(sender->duration());
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_RTCDtmfSender_interToneGap
(JNIEnv * env, jobject caller)
{
	webrtc::DtmfSenderInterface * sender = GetHandle<webrtc::DtmfSenderInterface>(env, caller);
	CHECK_HANDLEV(sender, 0);

	return static_cast<jint>(sender->inter_tone_gap());
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCDtmfSender_registerObserver
(JNIEnv * env, jobject caller, jobject jObserver)
{
	try {
		webrtc::DtmfSenderInterface * sender = GetHandle<webrtc::DtmfSenderInterface>(env, caller);
		CHECK_HANDLE(sender);

		auto observer = new jni::RTCDtmfSenderObserver(env, jni::JavaGlobalRef<jobject>(env, jObserver));

		// Registering replaces whatever observer the DTMF sender had, also one
		// registered through another Java object. It runs on the signaling
		// thread, which is also the thread that calls the observer, so the one
		// registered through this object before is out of use once it returns.
		jni::RegisteredObservers::Set(sender, observer);

		sender->RegisterObserver(observer);

		ReplaceNativeObserver(env, caller, "observerHandle", observer);
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCDtmfSender_unregisterObserver
(JNIEnv * env, jobject caller)
{
	webrtc::DtmfSenderInterface * sender = GetHandle<webrtc::DtmfSenderInterface>(env, caller);
	CHECK_HANDLE(sender);

	UnregisterOwnObserver(env, caller, sender);
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCDtmfSender_dispose
(JNIEnv * env, jobject caller)
{
	webrtc::DtmfSenderInterface * sender = GetHandle<webrtc::DtmfSenderInterface>(env, caller);
	CHECK_HANDLE(sender);

	// WebRTC must let go of an observer registered through this object before
	// it is deleted.
	UnregisterOwnObserver(env, caller, sender);

	// The RTCRtpSender and other Java objects keep their own references, so
	// this one need not be the last.
	sender->Release();

	SetHandle<std::nullptr_t>(env, caller, nullptr);
}