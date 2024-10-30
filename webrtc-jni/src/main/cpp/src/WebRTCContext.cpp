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
#include "Exception.h"
#include "JavaClassLoader.h"
#include "JavaError.h"
#include "JavaEnums.h"
#include "JavaFactories.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

#include "api/peer_connection_interface.h"
#include "modules/desktop_capture/desktop_capturer.h"
#include "rtc_base/ssl_adapter.h"

#ifdef _WIN32
#include <windows.h>
#include "media/audio/windows/WindowsAudioDeviceManager.h"
#include "media/video/windows/WindowsVideoDeviceManager.h"
#include "media/video/desktop/windows/WindowsPowerManagement.h"
#endif
#ifdef __linux__
#include "media/audio/linux/PulseAudioDeviceManager.h"
#include "media/video/linux/V4l2VideoDeviceManager.h"
#include "media/video/desktop/linux/LinuxPowerManagement.h"
#endif
#ifdef __APPLE__
#include "media/audio/macos/CoreAudioDeviceManager.h"
#include "media/video/macos/AVFVideoDeviceManager.h"
#endif

#include <memory>

namespace jni
{
	WebRTCContext::WebRTCContext(JavaVM * vm) :
		JavaContext(vm),
		audioDevManager(nullptr),
		videoDevManager(nullptr),
		powerManagement(nullptr)
	{
	}

	void WebRTCContext::initialize(JNIEnv * env)
	{
		if (!rtc::InitializeSSL()) {
			throw Exception("Initialize SSL failed");
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
		JavaEnums::add<webrtc::AudioDeviceModule::AudioLayer>(env, PKG_AUDIO"AudioLayer");
		JavaEnums::add<webrtc::AudioProcessing::Config::NoiseSuppression::Level>(env, PKG_AUDIO"AudioProcessingConfig$NoiseSuppression$Level");
		JavaEnums::add<jni::RTCStats::RTCStatsType>(env, PKG"RTCStatsType");

		JavaFactories::add<webrtc::AudioSourceInterface>(env, PKG_MEDIA"audio/AudioTrackSource");
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

		initializeClassLoader(env, PKG_INTERNAL"NativeClassLoader");
	}

	void WebRTCContext::initializeClassLoader(JNIEnv* env, const char * loaderName)
	{
		jclass javaClass = FindClass(env, loaderName);

		if (ExceptionCheck(env)) {
			return;
		}

		auto javaGet = GetStaticMethod(env, javaClass, "getClassLoader", "()Ljava/lang/ClassLoader;");

		auto loaderRef = jni::JavaGlobalRef<jobject>(env, env->CallStaticObjectMethod(javaClass, javaGet));

		InitClassLoader(env, loaderRef);
	}

	void WebRTCContext::destroy(JNIEnv * env)
	{
		if (!rtc::CleanupSSL()) {
			env->Throw(jni::JavaError(env, "Cleanup SSL failed"));
		}

		audioDevManager = nullptr;
		videoDevManager = nullptr;
	}

	avdev::AudioDeviceManager * WebRTCContext::getAudioDeviceManager()
	{
		std::unique_lock<std::mutex> mlock(aMutex);

		if (audioDevManager == nullptr) {
			initializeAudioManager();
		}

		return audioDevManager.get();
	}

	avdev::VideoDeviceManager * WebRTCContext::getVideoDeviceManager()
	{
		std::unique_lock<std::mutex> mlock(vMutex);

		if (videoDevManager == nullptr) {
			initializeVideoManager();
		}

		return videoDevManager.get();
	}

	avdev::PowerManagement * WebRTCContext::getPowerManagement()
    {
    	std::unique_lock<std::mutex> mlock(vMutex);

    	if (powerManagement == nullptr) {
    		initializePowerManagement();
    	}

    	return powerManagement.get();
    }

	void WebRTCContext::initializeAudioManager()
	{
#ifdef _WIN32
		audioDevManager = std::make_unique<avdev::WindowsAudioDeviceManager>();
#endif
#ifdef __linux__
		audioDevManager = std::make_unique<avdev::PulseAudioDeviceManager>();
#endif
#ifdef __APPLE__
		audioDevManager = std::make_unique<avdev::CoreAudioDeviceManager>();
#endif
	}

	void WebRTCContext::initializeVideoManager()
	{
#ifdef _WIN32
		videoDevManager = std::make_unique<avdev::WindowsVideoDeviceManager>();
#endif
#ifdef __linux__
		videoDevManager = std::make_unique<avdev::V4l2VideoDeviceManager>();
#endif
#ifdef __APPLE__
		videoDevManager = std::make_unique<avdev::AVFVideoDeviceManager>();
#endif
	}

	void WebRTCContext::initializePowerManagement()
	{
#ifdef _WIN32
		powerManagement = std::make_unique<avdev::WindowsPowerManagement>();
#endif
#ifdef __linux__
		powerManagement = std::make_unique<avdev::LinuxPowerManagement>();
#endif
#ifdef __APPLE__
		//powerManagement = std::make_unique<avdev::MacPowerManagement>();
#endif
	}
}