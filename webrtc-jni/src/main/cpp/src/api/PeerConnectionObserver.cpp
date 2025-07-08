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

#include "api/PeerConnectionObserver.h"
#include "api/RTCIceCandidate.h"
#include "api/RTCPeerConnectionIceErrorEvent.h"
#include "api/WebRTCUtils.h"
#include "Exception.h"
#include "JavaArray.h"
#include "JavaClasses.h"
#include "JavaEnums.h"
#include "JavaFactories.h"
#include "JavaRuntimeException.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	PeerConnectionObserver::PeerConnectionObserver(JNIEnv * env, const JavaGlobalRef<jobject> & observer) :
		observer(observer),
		javaClass(JavaClasses::get<JavaPeerConnectionObserverClass>(env))
	{
	}

	void PeerConnectionObserver::OnConnectionChange(webrtc::PeerConnectionInterface::PeerConnectionState state)
	{
		JNIEnv * env = AttachCurrentThread();

		auto jState = JavaEnums::toJava(env, state);

		env->CallVoidMethod(observer, javaClass->onConnectionChange, jState.get());

		ExceptionCheck(env);
	}

	void PeerConnectionObserver::OnSignalingChange(webrtc::PeerConnectionInterface::SignalingState state)
	{
		JNIEnv * env = AttachCurrentThread();

		auto jState = JavaEnums::toJava(env, state);

		env->CallVoidMethod(observer, javaClass->onSignalingChange, jState.get());

		ExceptionCheck(env);
	}

	void PeerConnectionObserver::OnTrack(webrtc::scoped_refptr<webrtc::RtpTransceiverInterface> transceiver)
	{
		JNIEnv * env = AttachCurrentThread();

		auto jTransceiver = JavaFactories::create(env, transceiver.get());

		env->CallVoidMethod(observer, javaClass->onTrack, jTransceiver.get());

		ExceptionCheck(env);
	}

	void PeerConnectionObserver::OnAddTrack(webrtc::scoped_refptr<webrtc::RtpReceiverInterface> receiver, const std::vector<webrtc::scoped_refptr<webrtc::MediaStreamInterface>> & streams)
	{
		JNIEnv * env = AttachCurrentThread();

		JavaLocalRef<jobjectArray> streamArray;

		try {
			streamArray = createObjectArray(env, streams);

			auto jReceiver = JavaFactories::create(env, receiver.get());

			env->CallVoidMethod(observer, javaClass->onAddTrack, jReceiver.get(), streamArray.get());
		}
		catch (...) {
			ThrowCxxJavaException(env);
		}

		ExceptionCheck(env);
	}

	void PeerConnectionObserver::OnRemoveTrack(webrtc::scoped_refptr<webrtc::RtpReceiverInterface> receiver)
	{
		JNIEnv * env = AttachCurrentThread();

		auto jReceiver = JavaFactories::create(env, receiver.get());

		env->CallVoidMethod(observer, javaClass->onRemoveTrack, jReceiver.get());

		ExceptionCheck(env);
	}

	void PeerConnectionObserver::OnDataChannel(webrtc::scoped_refptr<webrtc::DataChannelInterface> channel)
	{
		JNIEnv * env = AttachCurrentThread();

		auto jDataChannel = JavaFactories::create(env, channel.release());

		env->CallVoidMethod(observer, javaClass->onDataChannel, jDataChannel.get());

		ExceptionCheck(env);
	}

	void PeerConnectionObserver::OnRenegotiationNeeded()
	{
		JNIEnv * env = AttachCurrentThread();

		env->CallVoidMethod(observer, javaClass->onRenegotiationNeeded);

		ExceptionCheck(env);
	}

	void PeerConnectionObserver::OnIceConnectionChange(webrtc::PeerConnectionInterface::IceConnectionState state)
	{
		JNIEnv * env = AttachCurrentThread();

		auto jState = JavaEnums::toJava(env, state);

		env->CallVoidMethod(observer, javaClass->onIceConnectionChange, jState.get());

		ExceptionCheck(env);
	}

	void PeerConnectionObserver::OnIceGatheringChange(webrtc::PeerConnectionInterface::IceGatheringState state)
	{
		JNIEnv * env = AttachCurrentThread();

		auto jState = JavaEnums::toJava(env, state);

		env->CallVoidMethod(observer, javaClass->onIceGatheringChange, jState.get());

		ExceptionCheck(env);
	}

	void PeerConnectionObserver::OnIceCandidate(const webrtc::IceCandidateInterface * candidate)
	{
		JNIEnv * env = AttachCurrentThread();

		JavaLocalRef<jobject> jCandidate = RTCIceCandidate::toJava(env, candidate);

		env->CallVoidMethod(observer, javaClass->onIceCandidate, jCandidate.get());

		ExceptionCheck(env);
	}

	void PeerConnectionObserver::OnIceCandidateError(const std::string & address, int port, const std::string & url, int error_code, const std::string & error_text)
	{
		JNIEnv * env = AttachCurrentThread();

		JavaLocalRef<jobject> event = RTCPeerConnectionIceErrorEvent::toJava(env, address, port, url, error_code, error_text);

		env->CallVoidMethod(observer, javaClass->onIceCandidateError, event.get());

		ExceptionCheck(env);
	}

	void PeerConnectionObserver::OnIceCandidatesRemoved(const std::vector<webrtc::Candidate> & candidates)
	{
		JNIEnv * env = AttachCurrentThread();

		const auto eventClass = JavaClasses::get<RTCPeerConnectionIceErrorEvent::JavaRTCPeerConnectionIceErrorEventClass>(env);

		try {
			JavaLocalRef<jobjectArray> jCandidates = JavaArray::createObjectArray(env, candidates, eventClass->cls, &RTCIceCandidate::toJavaCricket);

			env->CallVoidMethod(observer, javaClass->onIceCandidatesRemoved, jCandidates.get());
		}
		catch (const Exception & e) {
			env->Throw(jni::JavaRuntimeException(env, e.what()));
		}
		catch (...) {
			ThrowCxxJavaException(env);
		}

		ExceptionCheck(env);
	}

	void PeerConnectionObserver::OnIceConnectionReceivingChange(bool receiving)
	{
		JNIEnv * env = AttachCurrentThread();

		env->CallVoidMethod(observer, javaClass->onIceConnectionReceivingChange, receiving);

		ExceptionCheck(env);
	}

	PeerConnectionObserver::JavaPeerConnectionObserverClass::JavaPeerConnectionObserverClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG"PeerConnectionObserver");

		onConnectionChange = GetMethod(env, cls, "onConnectionChange", "(L" PKG "RTCPeerConnectionState;)V");
		onSignalingChange = GetMethod(env, cls, "onSignalingChange", "(L" PKG "RTCSignalingState;)V");
		onTrack = GetMethod(env, cls, "onTrack", "(L" PKG "RTCRtpTransceiver;)V");
		onAddTrack = GetMethod(env, cls, "onAddTrack", "(L" PKG "RTCRtpReceiver;[L" PKG_MEDIA "MediaStream;)V");
		onRemoveTrack = GetMethod(env, cls, "onRemoveTrack", "(L" PKG "RTCRtpReceiver;)V");
		onDataChannel = GetMethod(env, cls, "onDataChannel", "(L" PKG "RTCDataChannel;)V");
		onRenegotiationNeeded = GetMethod(env, cls, "onRenegotiationNeeded", "()V");
		onIceConnectionChange = GetMethod(env, cls, "onIceConnectionChange", "(L" PKG "RTCIceConnectionState;)V");
		onIceGatheringChange = GetMethod(env, cls, "onIceGatheringChange", "(L" PKG "RTCIceGatheringState;)V");
		onIceCandidate = GetMethod(env, cls, "onIceCandidate", "(L" PKG "RTCIceCandidate;)V");
		onIceCandidateError = GetMethod(env, cls, "onIceCandidateError", "(L" PKG "RTCPeerConnectionIceErrorEvent;)V");
		onIceCandidatesRemoved = GetMethod(env, cls, "onIceCandidatesRemoved", "([L" PKG "RTCIceCandidate;)V");
		onIceConnectionReceivingChange = GetMethod(env, cls, "onIceConnectionReceivingChange", "(Z)V");
	}
}
