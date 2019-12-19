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

#include "WebRTCContext.h"
#include "api/DataBufferFactory.h"
#include "api/RTCStats.h"
#include "JavaError.h"
#include "JavaEnums.h"
#include "JavaFactories.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

#include "api/peer_connection_interface.h"
#include "modules/desktop_capture/desktop_capturer.h"
#include "rtc_base/ssl_adapter.h"

#include <memory>

namespace jni
{
	WebRTCContext::WebRTCContext(JavaVM * vm) :
		JavaContext(vm)
	{
	}

	void WebRTCContext::initialize(JNIEnv * env)
	{
		JavaContext::initialize(env);

		if (!rtc::InitializeSSL()) {
			env->Throw(jni::JavaError(env, "Initialize SSL failed"));
			return;
		}
		
		JavaEnums::add<rtc::LoggingSeverity>(env, PKG_LOG"Logging$Severity");
		JavaEnums::add<cricket::MediaType>(env, PKG_MEDIA"MediaType");
		JavaEnums::add<webrtc::DataChannelInterface::DataState>(env, PKG"RTCDataChannelState");
		JavaEnums::add<webrtc::DesktopCapturer::Result>(env, PKG_DESKTOP"DesktopCapturer$Result");
		JavaEnums::add<webrtc::DtlsTransportState>(env, PKG"RTCDtlsTransportState");
		JavaEnums::add<webrtc::DtxStatus>(env, PKG"RTCDtxStatus");
		JavaEnums::add<webrtc::MediaSourceInterface::SourceState>(env, PKG_MEDIA"MediaSource$State");
		JavaEnums::add<webrtc::MediaStreamTrackInterface::TrackState>(env, PKG_MEDIA"MediaStreamTrackState");
		JavaEnums::add<webrtc::PeerConnectionInterface::BundlePolicy>(env, PKG"RTCBundlePolicy");
		JavaEnums::add<webrtc::PeerConnectionInterface::IceConnectionState>(env, PKG"RTCIceConnectionState");
		JavaEnums::add<webrtc::PeerConnectionInterface::IceGatheringState>(env, PKG"RTCIceGatheringState");
		JavaEnums::add<webrtc::PeerConnectionInterface::IceTransportsType>(env, PKG"RTCIceTransportPolicy");
		JavaEnums::add<webrtc::PeerConnectionInterface::PeerConnectionState>(env, PKG"RTCPeerConnectionState");
		JavaEnums::add<webrtc::PeerConnectionInterface::RtcpMuxPolicy>(env, PKG"RTCRtcpMuxPolicy");
		JavaEnums::add<webrtc::PeerConnectionInterface::SignalingState>(env, PKG"RTCSignalingState");
		JavaEnums::add<webrtc::PeerConnectionInterface::TlsCertPolicy>(env, PKG"TlsCertPolicy");
		JavaEnums::add<webrtc::RtpTransceiverDirection>(env, PKG"RTCRtpTransceiverDirection");
		JavaEnums::add<webrtc::SdpType>(env, PKG"RTCSdpType");
		JavaEnums::add<jni::RTCStats::RTCStatsType>(env, PKG"RTCStatsType");

		JavaFactories::add<webrtc::AudioSourceInterface>(env, PKG_MEDIA"audio/AudioSource");
		JavaFactories::add<webrtc::AudioTrackInterface>(env, PKG_MEDIA"audio/AudioTrack");
		JavaFactories::add<webrtc::VideoTrackInterface>(env, PKG_MEDIA"video/VideoTrack");
		JavaFactories::add<webrtc::MediaStreamInterface>(env, PKG_MEDIA"MediaStream");
		JavaFactories::add<webrtc::DataChannelInterface>(env, PKG"RTCDataChannel");
		JavaFactories::add<webrtc::DtlsTransportInterface>(env, PKG"RTCDtlsTransport");
		JavaFactories::add<webrtc::IceTransportInterface>(env, PKG"RTCIceTransport");
		JavaFactories::add<webrtc::PeerConnectionInterface>(env, PKG"RTCPeerConnection");
		JavaFactories::add<webrtc::RtpReceiverInterface>(env, PKG"RTCRtpReceiver");
		JavaFactories::add<webrtc::RtpSenderInterface>(env, PKG"RTCRtpSender");
		JavaFactories::add<webrtc::RtpTransceiverInterface>(env, PKG"RTCRtpTransceiver");
		JavaFactories::add<webrtc::DataBuffer>(std::make_unique<DataBufferFactory>(env, PKG"RTCDataChannelBuffer"));
	}

	void WebRTCContext::destroy(JNIEnv * env)
	{
		if (!rtc::CleanupSSL()) {
			env->Throw(jni::JavaError(env, "Cleanup SSL failed"));
		}

		JavaContext::destroy(env);
	}
}