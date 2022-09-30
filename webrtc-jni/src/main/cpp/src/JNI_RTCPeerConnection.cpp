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

#include "JNI_RTCPeerConnection.h"
#include "api/CreateSessionDescriptionObserver.h"
#include "api/SetSessionDescriptionObserver.h"
#include "api/RTCAnswerOptions.h"
#include "api/RTCConfiguration.h"
#include "api/RTCDataChannelInit.h"
#include "api/RTCIceCandidate.h"
#include "api/RTCOfferOptions.h"
#include "api/RTCRtpTransceiverInit.h"
#include "api/RTCSessionDescription.h"
#include "api/RTCStatsCollectorCallback.h"
#include "api/WebRTCUtils.h"
#include "JavaArray.h"
#include "JavaEnums.h"
#include "JavaError.h"
#include "JavaFactories.h"
#include "JavaIterable.h"
#include "JavaList.h"
#include "JavaNullPointerException.h"
#include "JavaRef.h"
#include "JavaRuntimeException.h"
#include "JavaString.h"
#include "JavaUtils.h"

#include "api/peer_connection_interface.h"
#include "api/rtp_receiver_interface.h"
#include "api/rtp_sender_interface.h"

#include <string>
#include <vector>

JNIEXPORT jobjectArray JNICALL Java_dev_onvoid_webrtc_RTCPeerConnection_getSenders
(JNIEnv * env, jobject caller)
{
	webrtc::PeerConnectionInterface * pc = GetHandle<webrtc::PeerConnectionInterface>(env, caller);
	CHECK_HANDLEV(pc, nullptr);

	jni::JavaLocalRef<jobjectArray> objectArray;

	try {
		objectArray = jni::createObjectArray(env, pc->GetSenders());
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}

	return objectArray.release();
}

JNIEXPORT jobjectArray JNICALL Java_dev_onvoid_webrtc_RTCPeerConnection_getReceivers
(JNIEnv * env, jobject caller)
{
	webrtc::PeerConnectionInterface * pc = GetHandle<webrtc::PeerConnectionInterface>(env, caller);
	CHECK_HANDLEV(pc, nullptr);

	jni::JavaLocalRef<jobjectArray> objectArray;

	try {
		objectArray = jni::createObjectArray(env, pc->GetReceivers());
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}

	return objectArray.release();
}

JNIEXPORT jobjectArray JNICALL Java_dev_onvoid_webrtc_RTCPeerConnection_getTransceivers
(JNIEnv * env, jobject caller)
{
	webrtc::PeerConnectionInterface * pc = GetHandle<webrtc::PeerConnectionInterface>(env, caller);
	CHECK_HANDLEV(pc, nullptr);

	jni::JavaLocalRef<jobjectArray> objectArray;

	try {
		objectArray = jni::createObjectArray(env, pc->GetTransceivers());
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}

	return objectArray.release();
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCPeerConnection_addTrack
(JNIEnv * env, jobject caller, jobject jTrack, jobject jStreamIds)
{
	if (jTrack == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "MediaStreamTrack must not be null"));
		return nullptr;
	}
	if (jStreamIds == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "Stream IDs must not be null"));
		return nullptr;
	}

	webrtc::PeerConnectionInterface * pc = GetHandle<webrtc::PeerConnectionInterface>(env, caller);
	CHECK_HANDLEV(pc, nullptr);

	webrtc::MediaStreamTrackInterface * track = GetHandle<webrtc::MediaStreamTrackInterface>(env, jTrack);
	CHECK_HANDLEV(track, nullptr);

	std::vector<std::string> streamIDs = jni::JavaList::toStringVector(env, jni::JavaLocalRef<jobject>(env, jStreamIds));

	auto result = pc->AddTrack(track, streamIDs);

	if (result.ok()) {
		auto sender = result.MoveValue();

		return jni::JavaFactories::create(env, sender.release()).release();
	}
	
	env->Throw(jni::JavaRuntimeException(env, jni::RTCErrorToString(result.error()).c_str()));

	return nullptr;
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCPeerConnection_removeTrack
(JNIEnv * env, jobject caller, jobject jSender)
{
	if (jSender == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "RTCRtpSender must not be null"));
		return;
	}

	webrtc::PeerConnectionInterface * pc = GetHandle<webrtc::PeerConnectionInterface>(env, caller);
	CHECK_HANDLE(pc);

	webrtc::RtpSenderInterface * sender = GetHandle<webrtc::RtpSenderInterface>(env, jSender);
	CHECK_HANDLE(sender);

	auto result = pc->RemoveTrackOrError(sender);

	if (!result.ok()) {
		env->Throw(jni::JavaRuntimeException(env, "Remove track (RTCRtpSender) failed: %s %s",
			ToString(result.type()), result.message()));
	}
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCPeerConnection_addTransceiver
(JNIEnv * env, jobject caller, jobject jTrack, jobject jTransceiverInit)
{
	if (jTrack == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "MediaStreamTrack must not be null"));
		return nullptr;
	}

	webrtc::PeerConnectionInterface * pc = GetHandle<webrtc::PeerConnectionInterface>(env, caller);
	CHECK_HANDLEV(pc, nullptr);

	webrtc::MediaStreamTrackInterface * track = GetHandle<webrtc::MediaStreamTrackInterface>(env, jTrack);
	CHECK_HANDLEV(track, nullptr);

	webrtc::RTCErrorOr<rtc::scoped_refptr<webrtc::RtpTransceiverInterface>> result;

	if (jTransceiverInit != nullptr) {
		auto init = jni::RTCRtpTransceiverInit::toNative(env, jni::JavaLocalRef<jobject>(env, jTransceiverInit));

		result = pc->AddTransceiver(track, init);
	}
	else {
		result = pc->AddTransceiver(track);
	}

	if (result.ok()) {
		auto transceiver = result.MoveValue();

		return jni::JavaFactories::create(env, transceiver.release()).release();
	}

	env->Throw(jni::JavaRuntimeException(env, jni::RTCErrorToString(result.error()).c_str()));

	return nullptr;
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCPeerConnection_createDataChannel
(JNIEnv * env, jobject caller, jstring jLabel, jobject jDict)
{
	if (jLabel == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "Label must not be null"));
		return nullptr;
	}
	if (jDict == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "RTCDataChannelInit must not be null"));
		return nullptr;
	}

	webrtc::PeerConnectionInterface * pc = GetHandle<webrtc::PeerConnectionInterface>(env, caller);
	CHECK_HANDLEV(pc, nullptr);

	std::string label = jni::JavaString::toNative(env, jni::JavaLocalRef<jstring>(env, jLabel));
	webrtc::DataChannelInit dict = jni::RTCDataChannelInit::toNative(env, jni::JavaLocalRef<jobject>(env, jDict));

	try {
		auto result = pc->CreateDataChannelOrError(label, &dict);

		if (!result.ok()) {
			env->Throw(jni::JavaRuntimeException(env, "Create DataChannel failed: %s %s",
				ToString(result.error().type()), result.error().message()));

			return nullptr;
		}

		auto dataChannel = result.MoveValue();

		return jni::JavaFactories::create(env, dataChannel.release()).release();
	}
	catch (...) {
		ThrowCxxJavaException(env);
		return nullptr;
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCPeerConnection_createOffer
(JNIEnv * env, jobject caller, jobject jOptions, jobject jObserver)
{
	if (jOptions == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "RTCOfferOptions must not be null"));
		return;
	}
	if (jObserver == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "CreateSessionDescriptionObserver must not be null"));
		return;
	}

	webrtc::PeerConnectionInterface * pc = GetHandle<webrtc::PeerConnectionInterface>(env, caller);
	CHECK_HANDLE(pc);

	try {
		auto options = jni::RTCOfferOptions::toNative(env, jni::JavaLocalRef<jobject>(env, jOptions));
		auto observer = new rtc::RefCountedObject<jni::CreateSessionDescriptionObserver>(env, jni::JavaGlobalRef<jobject>(env, jObserver));

		pc->CreateOffer(observer, options);
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCPeerConnection_createAnswer
(JNIEnv * env, jobject caller, jobject jOptions, jobject jObserver)
{
	if (jOptions == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "RTCAnswerOptions must not be null"));
		return;
	}
	if (jObserver == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "CreateSessionDescriptionObserver must not be null"));
		return;
	}

	webrtc::PeerConnectionInterface * pc = GetHandle<webrtc::PeerConnectionInterface>(env, caller);
	CHECK_HANDLE(pc);

	try {
		auto options = jni::RTCAnswerOptions::toNative(env, jni::JavaLocalRef<jobject>(env, jOptions));
		auto observer = new rtc::RefCountedObject<jni::CreateSessionDescriptionObserver>(env, jni::JavaGlobalRef<jobject>(env, jObserver));

		pc->CreateAnswer(observer, options);
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCPeerConnection_getCurrentLocalDescription
(JNIEnv * env, jobject caller)
{
	webrtc::PeerConnectionInterface * pc = GetHandle<webrtc::PeerConnectionInterface>(env, caller);
	CHECK_HANDLEV(pc, nullptr);

	if (!pc->current_local_description()) {
		return nullptr;
	}

	return jni::RTCSessionDescription::toJava(env, pc->current_local_description()).release();
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCPeerConnection_getLocalDescription
(JNIEnv * env, jobject caller)
{
	webrtc::PeerConnectionInterface * pc = GetHandle<webrtc::PeerConnectionInterface>(env, caller);
	CHECK_HANDLEV(pc, nullptr);

	if (!pc->local_description()) {
		return nullptr;
	}

	return jni::RTCSessionDescription::toJava(env, pc->local_description()).release();
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCPeerConnection_getPendingLocalDescription
(JNIEnv * env, jobject caller)
{
	webrtc::PeerConnectionInterface * pc = GetHandle<webrtc::PeerConnectionInterface>(env, caller);
	CHECK_HANDLEV(pc, nullptr);

	if (!pc->pending_local_description()) {
		return nullptr;
	}

	return jni::RTCSessionDescription::toJava(env, pc->pending_local_description()).release();
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCPeerConnection_getCurrentRemoteDescription
(JNIEnv * env, jobject caller)
{
	webrtc::PeerConnectionInterface * pc = GetHandle<webrtc::PeerConnectionInterface>(env, caller);
	CHECK_HANDLEV(pc, nullptr);

	if (!pc->current_remote_description()) {
		return nullptr;
	}

	return jni::RTCSessionDescription::toJava(env, pc->current_remote_description()).release();
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCPeerConnection_getRemoteDescription
(JNIEnv * env, jobject caller)
{
	webrtc::PeerConnectionInterface * pc = GetHandle<webrtc::PeerConnectionInterface>(env, caller);
	CHECK_HANDLEV(pc, nullptr);

	if (!pc->remote_description()) {
		return nullptr;
	}

	return jni::RTCSessionDescription::toJava(env, pc->remote_description()).release();
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCPeerConnection_getPendingRemoteDescription
(JNIEnv * env, jobject caller)
{
	webrtc::PeerConnectionInterface * pc = GetHandle<webrtc::PeerConnectionInterface>(env, caller);
	CHECK_HANDLEV(pc, nullptr);

	if (!pc->pending_remote_description()) {
		return nullptr;
	}

	return jni::RTCSessionDescription::toJava(env, pc->pending_remote_description()).release();
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCPeerConnection_setLocalDescription
(JNIEnv * env, jobject caller, jobject jSessionDesc, jobject jobserver)
{
	if (jSessionDesc == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "RTCSessionDescription must not be null"));
		return;
	}
	if (jobserver == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "SetSessionDescriptionObserver must not be null"));
		return;
	}

	webrtc::PeerConnectionInterface * pc = GetHandle<webrtc::PeerConnectionInterface>(env, caller);
	CHECK_HANDLE(pc);

	try {
		auto desc = jni::RTCSessionDescription::toNative(env, jni::JavaLocalRef<jobject>(env, jSessionDesc));
		auto observer = new rtc::RefCountedObject<jni::SetSessionDescriptionObserver>(env, jni::JavaGlobalRef<jobject>(env, jobserver));

		pc->SetLocalDescription(observer, desc.release());
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCPeerConnection_setRemoteDescription
(JNIEnv * env, jobject caller, jobject jSessionDesc, jobject jobserver)
{
	if (jSessionDesc == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "RTCSessionDescription must not be null"));
		return;
	}
	if (jobserver == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "SetSessionDescriptionObserver must not be null"));
		return;
	}

	webrtc::PeerConnectionInterface * pc = GetHandle<webrtc::PeerConnectionInterface>(env, caller);
	CHECK_HANDLE(pc);

	try {
		auto desc = jni::RTCSessionDescription::toNative(env, jni::JavaLocalRef<jobject>(env, jSessionDesc));
		auto observer = new rtc::RefCountedObject<jni::SetSessionDescriptionObserver>(env, jni::JavaGlobalRef<jobject>(env, jobserver));

		pc->SetRemoteDescription(observer, desc.release());
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCPeerConnection_addIceCandidate
(JNIEnv * env, jobject caller, jobject jCandidate)
{
	if (jCandidate == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "RTCIceCandidate must not be null"));
		return;
	}

	webrtc::PeerConnectionInterface * pc = GetHandle<webrtc::PeerConnectionInterface>(env, caller);
	CHECK_HANDLE(pc);

	try {
		auto candidate = jni::RTCIceCandidate::toNative(env, jni::JavaLocalRef<jobject>(env, jCandidate));

		pc->AddIceCandidate(candidate.get());
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCPeerConnection_removeIceCandidates
(JNIEnv * env, jobject caller, jobject jCandidates)
{
	if (jCandidates == nullptr) {
		return;
	}

	webrtc::PeerConnectionInterface * pc = GetHandle<webrtc::PeerConnectionInterface>(env, caller);
	CHECK_HANDLE(pc);

	try {
		auto candidates = jni::JavaArray::toNativeVector<cricket::Candidate>(env,
			jni::static_java_ref_cast<jobjectArray>(env, jni::JavaLocalRef<jobject>(env, jCandidates)),
			&jni::RTCIceCandidate::toNativeCricket);

		if (!pc->RemoveIceCandidates(candidates)) {
			env->Throw(jni::JavaRuntimeException(env, "Remove ICE candidates from the peer connection failed"));
		}
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCPeerConnection_getSignalingState
(JNIEnv * env, jobject caller)
{
	webrtc::PeerConnectionInterface * pc = GetHandle<webrtc::PeerConnectionInterface>(env, caller);
	CHECK_HANDLE_DEFAULT(pc, jni::JavaEnums::toJava(env, webrtc::PeerConnectionInterface::SignalingState::kClosed).release());

	return jni::JavaEnums::toJava(env, pc->signaling_state()).release();
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCPeerConnection_getIceGatheringState
(JNIEnv * env, jobject caller)
{
	webrtc::PeerConnectionInterface * pc = GetHandle<webrtc::PeerConnectionInterface>(env, caller);
	CHECK_HANDLE_DEFAULT(pc, jni::JavaEnums::toJava(env, webrtc::PeerConnectionInterface::IceGatheringState::kIceGatheringNew).release());

	return jni::JavaEnums::toJava(env, pc->ice_gathering_state()).release();
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCPeerConnection_getIceConnectionState
(JNIEnv * env, jobject caller)
{
	webrtc::PeerConnectionInterface * pc = GetHandle<webrtc::PeerConnectionInterface>(env, caller);
	CHECK_HANDLE_DEFAULT(pc, jni::JavaEnums::toJava(env, webrtc::PeerConnectionInterface::IceConnectionState::kIceConnectionClosed).release());

	return jni::JavaEnums::toJava(env, pc->ice_connection_state()).release();
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCPeerConnection_getConnectionState
(JNIEnv * env, jobject caller)
{
	webrtc::PeerConnectionInterface * pc = GetHandle<webrtc::PeerConnectionInterface>(env, caller);
	CHECK_HANDLE_DEFAULT(pc, jni::JavaEnums::toJava(env, webrtc::PeerConnectionInterface::PeerConnectionState::kClosed).release());

	return jni::JavaEnums::toJava(env, pc->peer_connection_state()).release();
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_RTCPeerConnection_getConfiguration
(JNIEnv * env, jobject caller)
{
	webrtc::PeerConnectionInterface * pc = GetHandle<webrtc::PeerConnectionInterface>(env, caller);
	CHECK_HANDLEV(pc, nullptr);

	return jni::RTCConfiguration::toJava(env, pc->GetConfiguration()).release();
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCPeerConnection_setConfiguration
(JNIEnv * env, jobject caller, jobject jConfig)
{
	if (jConfig == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "RTCConfiguration must not be null"));
		return;
	}

	webrtc::PeerConnectionInterface * pc = GetHandle<webrtc::PeerConnectionInterface>(env, caller);
	CHECK_HANDLE(pc);

	auto config = jni::RTCConfiguration::toNative(env, jni::JavaLocalRef<jobject>(env, jConfig));

	webrtc::RTCError error = pc->SetConfiguration(config);

	if (!error.ok()) {
		env->Throw(jni::JavaRuntimeException(env, jni::RTCErrorToString(error).c_str()));
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCPeerConnection_getStats__Ldev_onvoid_webrtc_RTCStatsCollectorCallback_2
(JNIEnv * env, jobject caller, jobject jcallback)
{
	webrtc::PeerConnectionInterface * pc = GetHandle<webrtc::PeerConnectionInterface>(env, caller);
	CHECK_HANDLE(pc);

	if (jcallback == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "RTCStatsCollectorCallback is null"));
		return;
	}

	auto callback = new rtc::RefCountedObject<jni::RTCStatsCollectorCallback>(env, jni::JavaGlobalRef<jobject>(env, jcallback));

	pc->GetStats(callback);
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCPeerConnection_getStats__Ldev_onvoid_webrtc_RTCRtpReceiver_2Ldev_onvoid_webrtc_RTCStatsCollectorCallback_2
(JNIEnv * env, jobject caller, jobject jreceiver, jobject jcallback)
{
	webrtc::PeerConnectionInterface * pc = GetHandle<webrtc::PeerConnectionInterface>(env, caller);
	CHECK_HANDLE(pc);

	if (jreceiver == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "RTCRtpReceiver is null"));
		return;
	}
	if (jcallback == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "RTCStatsCollectorCallback is null"));
		return;
	}

	webrtc::RtpReceiverInterface * receiver = GetHandle<webrtc::RtpReceiverInterface>(env, jreceiver);
	CHECK_HANDLE(receiver);

	auto callback = new rtc::RefCountedObject<jni::RTCStatsCollectorCallback>(env, jni::JavaGlobalRef<jobject>(env, jcallback));

	pc->GetStats(receiver, callback);
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCPeerConnection_getStats__Ldev_onvoid_webrtc_RTCRtpSender_2Ldev_onvoid_webrtc_RTCStatsCollectorCallback_2
(JNIEnv * env, jobject caller, jobject jsender, jobject jcallback)
{
	webrtc::PeerConnectionInterface * pc = GetHandle<webrtc::PeerConnectionInterface>(env, caller);
	CHECK_HANDLE(pc);

	if (jsender == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "RTCRtpSender is null"));
		return;
	}
	if (jcallback == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "RTCStatsCollectorCallback is null"));
		return;
	}

	webrtc::RtpReceiverInterface * sender = GetHandle<webrtc::RtpReceiverInterface>(env, jsender);
	CHECK_HANDLE(sender);

	auto callback = new rtc::RefCountedObject<jni::RTCStatsCollectorCallback>(env, jni::JavaGlobalRef<jobject>(env, jcallback));

	pc->GetStats(sender, callback);
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCPeerConnection_restartIce
(JNIEnv * env, jobject caller)
{
	webrtc::PeerConnectionInterface * pc = GetHandle<webrtc::PeerConnectionInterface>(env, caller);
	CHECK_HANDLE(pc);

	pc->RestartIce();
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCPeerConnection_close
(JNIEnv * env, jobject caller)
{
	webrtc::PeerConnectionInterface * pc = GetHandle<webrtc::PeerConnectionInterface>(env, caller);
	CHECK_HANDLE(pc);

	try {
		pc->Close();

		SetHandle<std::nullptr_t>(env, caller, nullptr);

		auto observer = GetHandle<webrtc::PeerConnectionObserver>(env, caller, "observerHandle");

		if (observer) {
			delete observer;
		}
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}