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

#ifndef JNI_WEBRTC_API_PEER_CONNECTION_OBSERVER_H_
#define JNI_WEBRTC_API_PEER_CONNECTION_OBSERVER_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include "api/peer_connection_interface.h"

#include <jni.h>
#include <memory>

namespace jni
{
	class PeerConnectionObserver : public webrtc::PeerConnectionObserver
	{
		public:
			PeerConnectionObserver(JNIEnv * env, const JavaGlobalRef<jobject> & observer);
			virtual ~PeerConnectionObserver() = default;

			// PeerConnectionObserver implementation.
			void OnConnectionChange(webrtc::PeerConnectionInterface::PeerConnectionState state) override;
			void OnSignalingChange(webrtc::PeerConnectionInterface::SignalingState state) override;
			void OnTrack(rtc::scoped_refptr<webrtc::RtpTransceiverInterface> transceiver) override;
			void OnAddTrack(rtc::scoped_refptr<webrtc::RtpReceiverInterface> receiver, const std::vector<rtc::scoped_refptr<webrtc::MediaStreamInterface>> & streams) override;
			void OnRemoveTrack(rtc::scoped_refptr<webrtc::RtpReceiverInterface> receiver) override;
			void OnDataChannel(rtc::scoped_refptr<webrtc::DataChannelInterface> channel) override;
			void OnRenegotiationNeeded() override;
			void OnIceConnectionChange(webrtc::PeerConnectionInterface::IceConnectionState state) override;
			void OnIceGatheringChange(webrtc::PeerConnectionInterface::IceGatheringState state) override;
			void OnIceCandidate(const webrtc::IceCandidateInterface * candidate) override;
			void OnIceCandidateError(const std::string & address, int port, const std::string & url, int error_code, const std::string & error_text) override;
			void OnIceCandidatesRemoved(const std::vector<cricket::Candidate> & candidates) override;
			void OnIceConnectionReceivingChange(bool receiving) override;

		private:
			class JavaPeerConnectionObserverClass : public JavaClass
			{
				public:
					explicit JavaPeerConnectionObserverClass(JNIEnv * env);

					jmethodID onConnectionChange;
					jmethodID onSignalingChange;
					jmethodID onTrack;
					jmethodID onAddTrack;
					jmethodID onRemoveTrack;
					jmethodID onDataChannel;
					jmethodID onRenegotiationNeeded;
					jmethodID onIceConnectionChange;
					jmethodID onIceGatheringChange;
					jmethodID onIceCandidate;
					jmethodID onIceCandidateError;
					jmethodID onIceCandidatesRemoved;
					jmethodID onIceConnectionReceivingChange;
			};

		private:
			JavaGlobalRef<jobject> observer;

			const std::shared_ptr<JavaPeerConnectionObserverClass> javaClass;
	};
}

#endif