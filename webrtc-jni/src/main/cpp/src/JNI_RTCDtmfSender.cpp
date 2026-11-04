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
#include "NativeHandle.h"
#include "api/ObserverSlot.h"
#include "api/RegisteredObservers.h"
#include "api/RTCDtmfSenderObserver.h"
#include "JavaString.h"
#include "JavaUtils.h"

#include "api/dtmf_sender_interface.h"

namespace
{
	using DtmfSenderObserverSlot = jni::ObserverSlot<jni::RTCDtmfSenderObserver>;

	// Registering replaces whatever observer the DTMF sender had, also one
	// registered through another Java object. It runs on the signaling thread,
	// which is also the thread that calls the observer, so the ones replaced
	// are out of use once it returns. WebRTC is told of a removal only while
	// the observer removed is still the registered one: another Java object
	// for the same DTMF sender may have registered its own since, which stays.
	DtmfSenderObserverSlot::Apply ApplyTo(webrtc::DtmfSenderInterface * sender)
	{
		return jni::RegisteredObservers::Apply<jni::RTCDtmfSenderObserver>(sender,
			[sender](jni::RTCDtmfSenderObserver * observer) { sender->RegisterObserver(observer); },
			[sender]() { sender->UnregisterObserver(); });
	}
}

JNIEXPORT jboolean JNICALL Java_dev_onvoid_webrtc_RTCDtmfSender_canInsertDtmf
(JNIEnv * env, jobject caller)
{
	jni::NativeLease<webrtc::DtmfSenderInterface> sender(env, caller);
	CHECK_HANDLEV(sender, false);

	return static_cast<jboolean>(sender->CanInsertDtmf());
}

JNIEXPORT jboolean JNICALL Java_dev_onvoid_webrtc_RTCDtmfSender_insertDtmf
(JNIEnv * env, jobject caller, jstring jTones, jint duration, jint interToneGap)
{
	try {
		jni::NativeLease<webrtc::DtmfSenderInterface> sender(env, caller);
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
		jni::NativeLease<webrtc::DtmfSenderInterface> sender(env, caller);
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
	jni::NativeLease<webrtc::DtmfSenderInterface> sender(env, caller);
	CHECK_HANDLEV(sender, 0);

	return static_cast<jint>(sender->duration());
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_RTCDtmfSender_interToneGap
(JNIEnv * env, jobject caller)
{
	jni::NativeLease<webrtc::DtmfSenderInterface> sender(env, caller);
	CHECK_HANDLEV(sender, 0);

	return static_cast<jint>(sender->inter_tone_gap());
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCDtmfSender_registerObserver
(JNIEnv * env, jobject caller, jobject jObserver)
{
	try {
		jni::NativeLease<webrtc::DtmfSenderInterface> sender(env, caller);
		CHECK_HANDLE(sender);

		auto observer = new jni::RTCDtmfSenderObserver(env, jni::JavaGlobalRef<jobject>(env, jObserver));

		DtmfSenderObserverSlot::Set(env, caller, "observerHandle", observer, ApplyTo(sender));
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCDtmfSender_unregisterObserver
(JNIEnv * env, jobject caller)
{
	try {
		jni::NativeLease<webrtc::DtmfSenderInterface> sender(env, caller);
		CHECK_HANDLE(sender);

		DtmfSenderObserverSlot::Set(env, caller, "observerHandle", nullptr, ApplyTo(sender));
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCDtmfSender_dispose
(JNIEnv * env, jobject caller)
{
	webrtc::DtmfSenderInterface * sender = jni::TakeHandle<webrtc::DtmfSenderInterface>(env, caller);

	if (sender == nullptr) {
		return;
	}

	auto slot = DtmfSenderObserverSlot::Take(env, caller, "observerHandle");

	// Runs once no other thread registers through this object any more. WebRTC
	// must let go of an observer registered through this object before it is
	// deleted with the slot. The RTCRtpSender and other Java objects keep their
	// own references, so this one need not be the last.
	jni::ReleaseHandle(env, sender, [sender, slot](bool) {
		if (slot && jni::RegisteredObservers::Remove(sender, slot->applied())) {
			sender->UnregisterObserver();
		}

		sender->Release();
	});
}