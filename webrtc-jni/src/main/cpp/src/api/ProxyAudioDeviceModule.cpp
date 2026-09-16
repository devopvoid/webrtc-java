/*
 * Copyright 2026 Alex Andres
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

#include "api/ProxyAudioDeviceModule.h"

#include "api/make_ref_counted.h"
#include "rtc_base/logging.h"

#include <cstring>
#include <utility>

namespace jni
{
	webrtc::scoped_refptr<ProxyAudioDeviceModule> ProxyAudioDeviceModule::Create(
			webrtc::scoped_refptr<webrtc::AudioDeviceModule> delegate)
	{
		return webrtc::make_ref_counted<ProxyAudioDeviceModule>(std::move(delegate));
	}

	ProxyAudioDeviceModule::ProxyAudioDeviceModule(webrtc::scoped_refptr<webrtc::AudioDeviceModule> delegate) :
			delegate_(std::move(delegate)),
			captureEnabled_(false),
			gate_(captureEnabled_),
			gateRegistered_(false)
	{
	}

	ProxyAudioDeviceModule::~ProxyAudioDeviceModule()
	{
		// WebRTC unregisters its transport before releasing the factory. Should
		// the wrapped module still point at the gate, detach it so the module
		// does not keep a dangling pointer once this proxy is gone. A module
		// rejects this while its playout or recording is running, so warn rather
		// than stop media the application started itself.
		if (gateRegistered_ && delegate_->RegisterAudioCallback(nullptr) != 0) {
			RTC_LOG(LS_WARNING) << "ProxyAudioDeviceModule: the AudioDeviceModule kept its audio callback. "
								   "Stop its playout and recording before disposing the PeerConnectionFactory.";
		}
	}

	void ProxyAudioDeviceModule::SetCaptureEnabled(bool enabled)
	{
		captureEnabled_.store(enabled, std::memory_order_release);

		RTC_LOG(LS_INFO) << "ProxyAudioDeviceModule: device capture " << (enabled ? "enabled" : "disabled");

		if (!enabled && delegate_->Recording()) {
			// A recording that is already running was started by the application
			// through the Java AudioDeviceModule, which holds the wrapped module
			// and does not pass through this proxy. Leave it running; its frames
			// are dropped instead. WebRTC cannot have started it, because it only
			// starts a recording while capture is enabled.
			RTC_LOG(LS_INFO) << "ProxyAudioDeviceModule: dropping recorded audio, send streams are sink-fed";
		}
	}

	bool ProxyAudioDeviceModule::CaptureEnabled() const
	{
		return captureEnabled_.load(std::memory_order_acquire);
	}

	int32_t ProxyAudioDeviceModule::ActiveAudioLayer(AudioLayer * audioLayer) const
	{
		return delegate_->ActiveAudioLayer(audioLayer);
	}

	int32_t ProxyAudioDeviceModule::RegisterAudioCallback(webrtc::AudioTransport * audioCallback)
	{
		gate_.SetTarget(audioCallback);

		int32_t result = delegate_->RegisterAudioCallback(audioCallback ? &gate_ : nullptr);

		if (result == 0) {
			gateRegistered_ = audioCallback != nullptr;
		}

		return result;
	}

	int32_t ProxyAudioDeviceModule::Init()
	{
		return delegate_->Init();
	}

	int32_t ProxyAudioDeviceModule::Terminate()
	{
		return delegate_->Terminate();
	}

	bool ProxyAudioDeviceModule::Initialized() const
	{
		return delegate_->Initialized();
	}

	int16_t ProxyAudioDeviceModule::PlayoutDevices()
	{
		return delegate_->PlayoutDevices();
	}

	int16_t ProxyAudioDeviceModule::RecordingDevices()
	{
		return delegate_->RecordingDevices();
	}

	int32_t ProxyAudioDeviceModule::PlayoutDeviceName(uint16_t index,
													  char name[webrtc::kAdmMaxDeviceNameSize],
													  char guid[webrtc::kAdmMaxGuidSize])
	{
		return delegate_->PlayoutDeviceName(index, name, guid);
	}

	int32_t ProxyAudioDeviceModule::RecordingDeviceName(uint16_t index,
														char name[webrtc::kAdmMaxDeviceNameSize],
														char guid[webrtc::kAdmMaxGuidSize])
	{
		return delegate_->RecordingDeviceName(index, name, guid);
	}

	int32_t ProxyAudioDeviceModule::SetPlayoutDevice(uint16_t index)
	{
		return delegate_->SetPlayoutDevice(index);
	}

	int32_t ProxyAudioDeviceModule::SetPlayoutDevice(WindowsDeviceType device)
	{
		return delegate_->SetPlayoutDevice(device);
	}

	int32_t ProxyAudioDeviceModule::SetRecordingDevice(uint16_t index)
	{
		return delegate_->SetRecordingDevice(index);
	}

	int32_t ProxyAudioDeviceModule::SetRecordingDevice(WindowsDeviceType device)
	{
		return delegate_->SetRecordingDevice(device);
	}

	int32_t ProxyAudioDeviceModule::PlayoutIsAvailable(bool * available)
	{
		return delegate_->PlayoutIsAvailable(available);
	}

	int32_t ProxyAudioDeviceModule::InitPlayout()
	{
		return delegate_->InitPlayout();
	}

	bool ProxyAudioDeviceModule::PlayoutIsInitialized() const
	{
		return delegate_->PlayoutIsInitialized();
	}

	int32_t ProxyAudioDeviceModule::RecordingIsAvailable(bool * available)
	{
		if (!CaptureEnabled()) {
			if (!available) {
				return -1;
			}
			*available = false;
			return 0;
		}

		return delegate_->RecordingIsAvailable(available);
	}

	int32_t ProxyAudioDeviceModule::InitRecording()
	{
		if (!CaptureEnabled()) {
			return -1;
		}

		return delegate_->InitRecording();
	}

	bool ProxyAudioDeviceModule::RecordingIsInitialized() const
	{
		return delegate_->RecordingIsInitialized();
	}

	int32_t ProxyAudioDeviceModule::StartPlayout()
	{
		return delegate_->StartPlayout();
	}

	int32_t ProxyAudioDeviceModule::StopPlayout()
	{
		return delegate_->StopPlayout();
	}

	bool ProxyAudioDeviceModule::Playing() const
	{
		return delegate_->Playing();
	}

	int32_t ProxyAudioDeviceModule::StartRecording()
	{
		if (!CaptureEnabled()) {
			return -1;
		}

		return delegate_->StartRecording();
	}

	int32_t ProxyAudioDeviceModule::StopRecording()
	{
		return delegate_->StopRecording();
	}

	bool ProxyAudioDeviceModule::Recording() const
	{
		return delegate_->Recording();
	}

	int32_t ProxyAudioDeviceModule::InitSpeaker()
	{
		return delegate_->InitSpeaker();
	}

	bool ProxyAudioDeviceModule::SpeakerIsInitialized() const
	{
		return delegate_->SpeakerIsInitialized();
	}

	int32_t ProxyAudioDeviceModule::InitMicrophone()
	{
		return delegate_->InitMicrophone();
	}

	bool ProxyAudioDeviceModule::MicrophoneIsInitialized() const
	{
		return delegate_->MicrophoneIsInitialized();
	}

	int32_t ProxyAudioDeviceModule::SpeakerVolumeIsAvailable(bool * available)
	{
		return delegate_->SpeakerVolumeIsAvailable(available);
	}

	int32_t ProxyAudioDeviceModule::SetSpeakerVolume(uint32_t volume)
	{
		return delegate_->SetSpeakerVolume(volume);
	}

	int32_t ProxyAudioDeviceModule::SpeakerVolume(uint32_t * volume) const
	{
		return delegate_->SpeakerVolume(volume);
	}

	int32_t ProxyAudioDeviceModule::MaxSpeakerVolume(uint32_t * maxVolume) const
	{
		return delegate_->MaxSpeakerVolume(maxVolume);
	}

	int32_t ProxyAudioDeviceModule::MinSpeakerVolume(uint32_t * minVolume) const
	{
		return delegate_->MinSpeakerVolume(minVolume);
	}

	int32_t ProxyAudioDeviceModule::MicrophoneVolumeIsAvailable(bool * available)
	{
		return delegate_->MicrophoneVolumeIsAvailable(available);
	}

	int32_t ProxyAudioDeviceModule::SetMicrophoneVolume(uint32_t volume)
	{
		return delegate_->SetMicrophoneVolume(volume);
	}

	int32_t ProxyAudioDeviceModule::MicrophoneVolume(uint32_t * volume) const
	{
		return delegate_->MicrophoneVolume(volume);
	}

	int32_t ProxyAudioDeviceModule::MaxMicrophoneVolume(uint32_t * maxVolume) const
	{
		return delegate_->MaxMicrophoneVolume(maxVolume);
	}

	int32_t ProxyAudioDeviceModule::MinMicrophoneVolume(uint32_t * minVolume) const
	{
		return delegate_->MinMicrophoneVolume(minVolume);
	}

	int32_t ProxyAudioDeviceModule::SpeakerMuteIsAvailable(bool * available)
	{
		return delegate_->SpeakerMuteIsAvailable(available);
	}

	int32_t ProxyAudioDeviceModule::SetSpeakerMute(bool enable)
	{
		return delegate_->SetSpeakerMute(enable);
	}

	int32_t ProxyAudioDeviceModule::SpeakerMute(bool * enabled) const
	{
		return delegate_->SpeakerMute(enabled);
	}

	int32_t ProxyAudioDeviceModule::MicrophoneMuteIsAvailable(bool * available)
	{
		return delegate_->MicrophoneMuteIsAvailable(available);
	}

	int32_t ProxyAudioDeviceModule::SetMicrophoneMute(bool enable)
	{
		return delegate_->SetMicrophoneMute(enable);
	}

	int32_t ProxyAudioDeviceModule::MicrophoneMute(bool * enabled) const
	{
		return delegate_->MicrophoneMute(enabled);
	}

	int32_t ProxyAudioDeviceModule::StereoPlayoutIsAvailable(bool * available) const
	{
		return delegate_->StereoPlayoutIsAvailable(available);
	}

	int32_t ProxyAudioDeviceModule::SetStereoPlayout(bool enable)
	{
		return delegate_->SetStereoPlayout(enable);
	}

	int32_t ProxyAudioDeviceModule::StereoPlayout(bool * enabled) const
	{
		return delegate_->StereoPlayout(enabled);
	}

	int32_t ProxyAudioDeviceModule::StereoRecordingIsAvailable(bool * available) const
	{
		return delegate_->StereoRecordingIsAvailable(available);
	}

	int32_t ProxyAudioDeviceModule::SetStereoRecording(bool enable)
	{
		return delegate_->SetStereoRecording(enable);
	}

	int32_t ProxyAudioDeviceModule::StereoRecording(bool * enabled) const
	{
		return delegate_->StereoRecording(enabled);
	}

	int32_t ProxyAudioDeviceModule::PlayoutDelay(uint16_t * delayMS) const
	{
		return delegate_->PlayoutDelay(delayMS);
	}

	bool ProxyAudioDeviceModule::BuiltInAECIsAvailable() const
	{
		return delegate_->BuiltInAECIsAvailable();
	}

	bool ProxyAudioDeviceModule::BuiltInAGCIsAvailable() const
	{
		return delegate_->BuiltInAGCIsAvailable();
	}

	bool ProxyAudioDeviceModule::BuiltInNSIsAvailable() const
	{
		return delegate_->BuiltInNSIsAvailable();
	}

	int32_t ProxyAudioDeviceModule::EnableBuiltInAEC(bool enable)
	{
		return delegate_->EnableBuiltInAEC(enable);
	}

	int32_t ProxyAudioDeviceModule::EnableBuiltInAGC(bool enable)
	{
		return delegate_->EnableBuiltInAGC(enable);
	}

	int32_t ProxyAudioDeviceModule::EnableBuiltInNS(bool enable)
	{
		return delegate_->EnableBuiltInNS(enable);
	}

	int32_t ProxyAudioDeviceModule::GetPlayoutUnderrunCount() const
	{
		return delegate_->GetPlayoutUnderrunCount();
	}

	std::optional<webrtc::AudioDeviceModule::Stats> ProxyAudioDeviceModule::GetStats() const
	{
		return delegate_->GetStats();
	}

	// TransportGate

	ProxyAudioDeviceModule::TransportGate::TransportGate(const std::atomic<bool> & captureEnabled) :
			captureEnabled_(captureEnabled),
			target_(nullptr)
	{
	}

	void ProxyAudioDeviceModule::TransportGate::SetTarget(webrtc::AudioTransport * target)
	{
		target_.store(target, std::memory_order_release);
	}

	int32_t ProxyAudioDeviceModule::TransportGate::RecordedDataIsAvailable(const void * audioSamples,
																		   size_t nSamples,
																		   size_t nBytesPerSample,
																		   size_t nChannels,
																		   uint32_t samplesPerSec,
																		   uint32_t totalDelayMS,
																		   int32_t clockDrift,
																		   uint32_t currentMicLevel,
																		   bool keyPressed,
																		   uint32_t & newMicLevel)
	{
		return RecordedDataIsAvailable(audioSamples, nSamples, nBytesPerSample, nChannels,
									   samplesPerSec, totalDelayMS, clockDrift, currentMicLevel,
									   keyPressed, newMicLevel, std::nullopt);
	}

	int32_t ProxyAudioDeviceModule::TransportGate::RecordedDataIsAvailable(const void * audioSamples,
																		   size_t nSamples,
																		   size_t nBytesPerSample,
																		   size_t nChannels,
																		   uint32_t samplesPerSec,
																		   uint32_t totalDelayMS,
																		   int32_t clockDrift,
																		   uint32_t currentMicLevel,
																		   bool keyPressed,
																		   uint32_t & newMicLevel,
																		   std::optional<int64_t> estimatedCaptureTimeNS)
	{
		newMicLevel = currentMicLevel;

		webrtc::AudioTransport * target = target_.load(std::memory_order_acquire);

		if (!target || !captureEnabled_.load(std::memory_order_acquire)) {
			// Drop the frame. Send streams of this factory are fed by their
			// tracks' sources, not by the device.
			return 0;
		}

		return target->RecordedDataIsAvailable(audioSamples, nSamples, nBytesPerSample, nChannels,
											   samplesPerSec, totalDelayMS, clockDrift, currentMicLevel,
											   keyPressed, newMicLevel, estimatedCaptureTimeNS);
	}

	int32_t ProxyAudioDeviceModule::TransportGate::NeedMorePlayData(size_t nSamples,
																	size_t nBytesPerSample,
																	size_t nChannels,
																	uint32_t samplesPerSec,
																	void * audioSamples,
																	size_t & nSamplesOut,
																	int64_t * elapsed_time_ms,
																	int64_t * ntp_time_ms)
	{
		webrtc::AudioTransport * target = target_.load(std::memory_order_acquire);

		if (!target) {
			// Set safe values for all out parameters, as the interface requires.
			std::memset(audioSamples, 0, nSamples * nBytesPerSample);
			nSamplesOut = nSamples;
			if (elapsed_time_ms) {
				*elapsed_time_ms = -1;
			}
			if (ntp_time_ms) {
				*ntp_time_ms = -1;
			}
			return 0;
		}

		return target->NeedMorePlayData(nSamples, nBytesPerSample, nChannels, samplesPerSec,
										audioSamples, nSamplesOut, elapsed_time_ms, ntp_time_ms);
	}

	void ProxyAudioDeviceModule::TransportGate::PullRenderData(int bits_per_sample,
															   int sample_rate,
															   size_t number_of_channels,
															   size_t number_of_frames,
															   void * audio_data,
															   int64_t * elapsed_time_ms,
															   int64_t * ntp_time_ms)
	{
		webrtc::AudioTransport * target = target_.load(std::memory_order_acquire);

		if (!target) {
			std::memset(audio_data, 0, number_of_frames * number_of_channels * (bits_per_sample / 8));
			if (elapsed_time_ms) {
				*elapsed_time_ms = -1;
			}
			if (ntp_time_ms) {
				*ntp_time_ms = -1;
			}
			return;
		}

		target->PullRenderData(bits_per_sample, sample_rate, number_of_channels, number_of_frames,
							   audio_data, elapsed_time_ms, ntp_time_ms);
	}
}
