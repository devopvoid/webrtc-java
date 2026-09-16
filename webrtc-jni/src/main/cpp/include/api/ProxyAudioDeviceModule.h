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

#ifndef JNI_WEBRTC_API_PROXY_AUDIO_DEVICE_MODULE_H_
#define JNI_WEBRTC_API_PROXY_AUDIO_DEVICE_MODULE_H_

#include "api/audio/audio_device.h"
#include "api/audio/audio_device_defines.h"
#include "api/scoped_refptr.h"

#include <atomic>
#include <cstdint>
#include <optional>

namespace jni
{
	// Wraps the AudioDeviceModule a PeerConnectionFactory is created with and
	// forwards every call to it. Its purpose is to gate the device capture path.
	//
	// WebRTC's AudioState starts the module's recording as soon as any audio
	// send stream starts and fans every recorded frame out to every send stream
	// of the factory (AudioTransportImpl::SendProcessedData). A send stream whose
	// track is backed by a sink-fed source (jni::CustomAudioSource, or a
	// forwarded remote track) already receives its audio through WebRTC's
	// LocalAudioSinkAdapter on the pushing thread. Feeding it from the capture
	// thread as well trips RTC_CHECK_RUNS_SERIALIZED in
	// AudioSendStream::SendAudioData and aborts the process, and interleaves
	// microphone frames with the pushed audio whenever it does not.
	//
	// While capture is disabled, InitRecording() and StartRecording() fail so
	// AudioState never opens the device, and recorded data that still arrives
	// from the wrapped module (from a recording the application started through
	// the Java AudioDeviceModule, which holds the wrapped module directly) is
	// dropped before it reaches WebRTC. Playout is never affected.
	//
	// Capture starts disabled and is enabled only once the application asks for
	// device-captured audio, which it can only do through
	// PeerConnectionFactory.createAudioSource(). A factory that sends nothing
	// but sink-fed audio therefore never opens the recording device, and one
	// that sends no audio at all never opens it either.
	class ProxyAudioDeviceModule : public webrtc::AudioDeviceModule
	{
		public:
			static webrtc::scoped_refptr<ProxyAudioDeviceModule> Create(
					webrtc::scoped_refptr<webrtc::AudioDeviceModule> delegate);

			explicit ProxyAudioDeviceModule(webrtc::scoped_refptr<webrtc::AudioDeviceModule> delegate);
			~ProxyAudioDeviceModule() override;

			// Enables or disables the device capture path. May be called from any
			// thread. Callers that must not interleave with AudioState starting a
			// recording should run this on the factory's worker thread.
			void SetCaptureEnabled(bool enabled);
			bool CaptureEnabled() const;

			// webrtc::AudioDeviceModule implementation.
			int32_t ActiveAudioLayer(AudioLayer * audioLayer) const override;
			int32_t RegisterAudioCallback(webrtc::AudioTransport * audioCallback) override;
			int32_t Init() override;
			int32_t Terminate() override;
			bool Initialized() const override;

			int16_t PlayoutDevices() override;
			int16_t RecordingDevices() override;
			int32_t PlayoutDeviceName(uint16_t index,
									  char name[webrtc::kAdmMaxDeviceNameSize],
									  char guid[webrtc::kAdmMaxGuidSize]) override;
			int32_t RecordingDeviceName(uint16_t index,
										char name[webrtc::kAdmMaxDeviceNameSize],
										char guid[webrtc::kAdmMaxGuidSize]) override;

			int32_t SetPlayoutDevice(uint16_t index) override;
			int32_t SetPlayoutDevice(WindowsDeviceType device) override;
			int32_t SetRecordingDevice(uint16_t index) override;
			int32_t SetRecordingDevice(WindowsDeviceType device) override;

			int32_t PlayoutIsAvailable(bool * available) override;
			int32_t InitPlayout() override;
			bool PlayoutIsInitialized() const override;
			int32_t RecordingIsAvailable(bool * available) override;
			int32_t InitRecording() override;
			bool RecordingIsInitialized() const override;

			int32_t StartPlayout() override;
			int32_t StopPlayout() override;
			bool Playing() const override;
			int32_t StartRecording() override;
			int32_t StopRecording() override;
			bool Recording() const override;

			int32_t InitSpeaker() override;
			bool SpeakerIsInitialized() const override;
			int32_t InitMicrophone() override;
			bool MicrophoneIsInitialized() const override;

			int32_t SpeakerVolumeIsAvailable(bool * available) override;
			int32_t SetSpeakerVolume(uint32_t volume) override;
			int32_t SpeakerVolume(uint32_t * volume) const override;
			int32_t MaxSpeakerVolume(uint32_t * maxVolume) const override;
			int32_t MinSpeakerVolume(uint32_t * minVolume) const override;

			int32_t MicrophoneVolumeIsAvailable(bool * available) override;
			int32_t SetMicrophoneVolume(uint32_t volume) override;
			int32_t MicrophoneVolume(uint32_t * volume) const override;
			int32_t MaxMicrophoneVolume(uint32_t * maxVolume) const override;
			int32_t MinMicrophoneVolume(uint32_t * minVolume) const override;

			int32_t SpeakerMuteIsAvailable(bool * available) override;
			int32_t SetSpeakerMute(bool enable) override;
			int32_t SpeakerMute(bool * enabled) const override;
			int32_t MicrophoneMuteIsAvailable(bool * available) override;
			int32_t SetMicrophoneMute(bool enable) override;
			int32_t MicrophoneMute(bool * enabled) const override;

			int32_t StereoPlayoutIsAvailable(bool * available) const override;
			int32_t SetStereoPlayout(bool enable) override;
			int32_t StereoPlayout(bool * enabled) const override;
			int32_t StereoRecordingIsAvailable(bool * available) const override;
			int32_t SetStereoRecording(bool enable) override;
			int32_t StereoRecording(bool * enabled) const override;

			int32_t PlayoutDelay(uint16_t * delayMS) const override;

			bool BuiltInAECIsAvailable() const override;
			bool BuiltInAGCIsAvailable() const override;
			bool BuiltInNSIsAvailable() const override;
			int32_t EnableBuiltInAEC(bool enable) override;
			int32_t EnableBuiltInAGC(bool enable) override;
			int32_t EnableBuiltInNS(bool enable) override;

			int32_t GetPlayoutUnderrunCount() const override;
			std::optional<Stats> GetStats() const override;

		private:
			// The AudioTransport registered with the wrapped module in place of
			// the one WebRTC registers with this proxy. Render calls are always
			// forwarded; recorded data is forwarded only while capture is enabled.
			class TransportGate : public webrtc::AudioTransport
			{
				public:
					explicit TransportGate(const std::atomic<bool> & captureEnabled);
					~TransportGate() override = default;

					void SetTarget(webrtc::AudioTransport * target);

					// webrtc::AudioTransport implementation.
					int32_t RecordedDataIsAvailable(const void * audioSamples,
													size_t nSamples,
													size_t nBytesPerSample,
													size_t nChannels,
													uint32_t samplesPerSec,
													uint32_t totalDelayMS,
													int32_t clockDrift,
													uint32_t currentMicLevel,
													bool keyPressed,
													uint32_t & newMicLevel) override;
					int32_t RecordedDataIsAvailable(const void * audioSamples,
													size_t nSamples,
													size_t nBytesPerSample,
													size_t nChannels,
													uint32_t samplesPerSec,
													uint32_t totalDelayMS,
													int32_t clockDrift,
													uint32_t currentMicLevel,
													bool keyPressed,
													uint32_t & newMicLevel,
													std::optional<int64_t> estimatedCaptureTimeNS) override;
					int32_t NeedMorePlayData(size_t nSamples,
											 size_t nBytesPerSample,
											 size_t nChannels,
											 uint32_t samplesPerSec,
											 void * audioSamples,
											 size_t & nSamplesOut,
											 int64_t * elapsed_time_ms,
											 int64_t * ntp_time_ms) override;
					void PullRenderData(int bits_per_sample,
										int sample_rate,
										size_t number_of_channels,
										size_t number_of_frames,
										void * audio_data,
										int64_t * elapsed_time_ms,
										int64_t * ntp_time_ms) override;

				private:
					const std::atomic<bool> & captureEnabled_;
					std::atomic<webrtc::AudioTransport *> target_;
			};

			webrtc::scoped_refptr<webrtc::AudioDeviceModule> delegate_;
			std::atomic<bool> captureEnabled_;
			TransportGate gate_;
			bool gateRegistered_;
	};
}

#endif
