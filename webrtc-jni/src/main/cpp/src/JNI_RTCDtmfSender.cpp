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
#include "api/RTCDtmfSenderObserver.h"
#include "JavaString.h"
#include "JavaUtils.h"

#include "api/dtmf_sender_interface.h"

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
	webrtc::DtmfSenderInterface * sender = GetHandle<webrtc::DtmfSenderInterface>(env, caller);
	CHECK_HANDLEV(sender, false);

	std::string tones = jni::JavaString::toNative(env, jni::JavaLocalRef<jstring>(env, jTones));

	return static_cast<jboolean>(sender->InsertDtmf(tones, static_cast<int>(duration), static_cast<int>(interToneGap)));
}

JNIEXPORT jstring JNICALL Java_dev_onvoid_webrtc_RTCDtmfSender_tones
(JNIEnv * env, jobject caller)
{
	webrtc::DtmfSenderInterface * sender = GetHandle<webrtc::DtmfSenderInterface>(env, caller);
	CHECK_HANDLEV(sender, nullptr);

	return jni::JavaString::toJava(env, sender->tones()).release();
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
	webrtc::DtmfSenderInterface * sender = GetHandle<webrtc::DtmfSenderInterface>(env, caller);
	CHECK_HANDLE(sender);

	sender->RegisterObserver(new jni::RTCDtmfSenderObserver(env, jni::JavaGlobalRef<jobject>(env, jObserver)));
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCDtmfSender_unregisterObserver
(JNIEnv * env, jobject caller)
{
	webrtc::DtmfSenderInterface * sender = GetHandle<webrtc::DtmfSenderInterface>(env, caller);
	CHECK_HANDLE(sender);

	sender->UnregisterObserver();
}