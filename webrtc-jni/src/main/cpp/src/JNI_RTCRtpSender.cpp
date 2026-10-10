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

#include "JNI_RTCRtpSender.h"
#include "api/RTCRtpSenderObserver.h"
#include "api/RTCRtpSendParameters.h"
#include "api/WebRTCUtils.h"
#include "JavaFactories.h"
#include "JavaArrayList.h"
#include "JavaList.h"
#include "JavaRef.h"
#include "JavaRuntimeException.h"
#include "JavaString.h"
#include "JavaUtils.h"

#include "api/EncodedFrameTransformer.h"
#include "api/RTCDtmfSender.h"
#include "api/rtp_sender_interface.h"
#include "media/MediaStreamTrackView.h"

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCRtpSender_getTrack
(JNIEnv * env, jobject caller)
{
	webrtc::RtpSenderInterface * sender = GetHandle<webrtc::RtpSenderInterface>(env, caller);
	CHECK_HANDLEV(sender, nullptr);

	webrtc::scoped_refptr<webrtc::MediaStreamTrackInterface> track = sender->track();

	// The sender keeps the track; the Java object is only a view of it.
	return jni::MediaStreamTrackView::create(env, track.get()).release();
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCRtpSender_getTransport
(JNIEnv * env, jobject caller)
{
	webrtc::RtpSenderInterface * sender = GetHandle<webrtc::RtpSenderInterface>(env, caller);
	CHECK_HANDLEV(sender, nullptr);

	auto transport = sender->dtls_transport();

	return jni::JavaFactories::create(env, transport.get()).release();
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCRtpSender_replaceTrack
(JNIEnv * env, jobject caller, jobject jTrack)
{
	webrtc::RtpSenderInterface * sender = GetHandle<webrtc::RtpSenderInterface>(env, caller);
	CHECK_HANDLE(sender);

	webrtc::MediaStreamTrackInterface * track = jTrack == nullptr
		? nullptr
		: GetHandle<webrtc::MediaStreamTrackInterface>(env, jTrack);

	if (!sender->SetTrack(track)) {
		env->Throw(jni::JavaRuntimeException(env, "Set track failed"));
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCRtpSender_setParameters
(JNIEnv * env, jobject caller, jobject jParams)
{
	webrtc::RtpSenderInterface * sender = GetHandle<webrtc::RtpSenderInterface>(env, caller);
	CHECK_HANDLE(sender);

	webrtc::RtpParameters rtp_parameters = jni::RTCRtpSendParameters::toNative(env, jni::JavaLocalRef<jobject>(env, jParams));
	webrtc::RTCError result = sender->SetParameters(rtp_parameters);

	if (!result.ok()) {
		env->Throw(jni::JavaRuntimeException(env, jni::RTCErrorToString(result).c_str()));
	}
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCRtpSender_getParameters
(JNIEnv * env, jobject caller)
{
	webrtc::RtpSenderInterface * sender = GetHandle<webrtc::RtpSenderInterface>(env, caller);
	CHECK_HANDLEV(sender, nullptr);

	return jni::RTCRtpSendParameters::toJava(env, sender->GetParameters()).release();
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCRtpSender_setStreams
(JNIEnv * env, jobject caller, jobject streamIdList)
{
	webrtc::RtpSenderInterface * sender = GetHandle<webrtc::RtpSenderInterface>(env, caller);
	CHECK_HANDLE(sender);

	std::vector<std::string> streamIDs = jni::JavaList::toStringVector(env, jni::JavaLocalRef<jobject>(env, streamIdList));

	sender->SetStreams(streamIDs);
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCRtpSender_getDtmfSender
(JNIEnv * env, jobject caller)
{
	webrtc::RtpSenderInterface * sender = GetHandle<webrtc::RtpSenderInterface>(env, caller);
	CHECK_HANDLEV(sender, nullptr);

	auto dtmfSender = sender->GetDtmfSender();
	if (!dtmfSender) {
		return nullptr;
	}

	auto jDtmfSender = jni::RTCDtmfSender::toJava(env);

	SetHandle(env, jDtmfSender, dtmfSender.get());

	return jDtmfSender.release();
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCRtpSender_setTransform
(JNIEnv * env, jobject caller, jobject jTransformer)
{
	webrtc::RtpSenderInterface * sender = GetHandle<webrtc::RtpSenderInterface>(env, caller);
	CHECK_HANDLE(sender);

	try {
		// Clearing a transform that was never set installs nothing.
		auto transformer = jTransformer != nullptr
			? jni::EncodedFrameTransformer::Of(sender)
			: jni::EncodedFrameTransformer::Find(sender);

		if (transformer) {
			transformer->SetTransformer(env, jTransformer);
		}
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCRtpSender_generateKeyFrame
(JNIEnv * env, jobject caller)
{
	webrtc::RtpSenderInterface * sender = GetHandle<webrtc::RtpSenderInterface>(env, caller);
	CHECK_HANDLE(sender);

	// Audio has no key frames, so there is nothing to ask for.
	if (sender->media_type() != webrtc::MediaType::VIDEO) {
		return;
	}

	webrtc::RTCError result = sender->GenerateKeyFrame({});

	if (!result.ok()) {
		env->Throw(jni::JavaRuntimeException(env, jni::RTCErrorToString(result).c_str()));
	}
}

JNIEXPORT jstring JNICALL Java_dev_onvoid_webrtc_RTCRtpSender_getId
(JNIEnv * env, jobject caller)
{
	webrtc::RtpSenderInterface * sender = GetHandle<webrtc::RtpSenderInterface>(env, caller);
	CHECK_HANDLEV(sender, nullptr);

	return jni::JavaString::toJava(env, sender->id()).release();
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCRtpSender_getStreams
(JNIEnv * env, jobject caller)
{
	webrtc::RtpSenderInterface * sender = GetHandle<webrtc::RtpSenderInterface>(env, caller);
	CHECK_HANDLEV(sender, nullptr);

	try {
		const std::vector<std::string> streamIds = sender->stream_ids();

		jni::JavaArrayList list(env, streamIds.size());

		for (const auto & id : streamIds) {
			list.add(jni::JavaString::toJava(env, id));
		}

		return list.listObject().release();
	}
	catch (...) {
		ThrowCxxJavaException(env);
		return nullptr;
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCRtpSender_setObserver
(JNIEnv * env, jobject caller, jobject jObserver)
{
	webrtc::RtpSenderInterface * sender = GetHandle<webrtc::RtpSenderInterface>(env, caller);
	CHECK_HANDLE(sender);

	try {
		jni::RTCRtpSenderObserver * observer = nullptr;

		if (jObserver != nullptr) {
			observer = new jni::RTCRtpSenderObserver(env, jni::JavaGlobalRef<jobject>(env, jObserver));
		}

		// Runs on the signaling thread, which is also the thread that calls
		// the observer, so the previous one is out of use once this returns.
		sender->SetObserver(observer);

		ReplaceNativeObserver(env, caller, "observerHandle", observer);
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCRtpSender_dispose
(JNIEnv * env, jobject caller)
{
	webrtc::RtpSenderInterface * sender = GetHandle<webrtc::RtpSenderInterface>(env, caller);
	CHECK_HANDLE(sender);

	// WebRTC must let go of an observer set through this object before it is
	// deleted.
	if (GetHandle<jni::RTCRtpSenderObserver>(env, caller, "observerHandle") != nullptr) {
		sender->SetObserver(nullptr);

		ClearNativeObserver<jni::RTCRtpSenderObserver>(env, caller, "observerHandle");
	}

	// Unlike e.g. MediaStreamTrack, an RTCRtpSender is not exclusively owned
	// by one Java wrapper: the owning RtpTransceiver keeps its own reference,
	// and other Java wrappers may have been obtained via separate
	// getSenders()/getSender() calls. Dropping our reference here is
	// expected to leave others around, so it is not reported as an error.
	sender->Release();

	SetHandle<std::nullptr_t>(env, caller, nullptr);
}