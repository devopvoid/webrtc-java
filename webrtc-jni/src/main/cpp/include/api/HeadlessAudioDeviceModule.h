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

#ifndef JNI_WEBRTC_API_HEADLESS_ADM_H_
#define JNI_WEBRTC_API_HEADLESS_ADM_H_

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>
#include <memory>

#include "api/environment/environment.h"
#include "api/environment/environment_factory.h"
#include "api/make_ref_counted.h"
#include "modules/audio_device/audio_device_buffer.h"
#include "modules/audio_device/include/audio_device.h"
#include "modules/audio_device/include/audio_device_defines.h"
#include "rtc_base/buffer.h"
#include "rtc_base/platform_thread.h"
#include "rtc_base/ref_counted_object.h"
#include "rtc_base/synchronization/mutex.h"
#include "rtc_base/thread.h"
#include "rtc_base/time_utils.h"

namespace jni
{
    // A playout-only, "discard" AudioDeviceModule that drives the render pipeline
    // by pulling 10 ms PCM chunks from AudioTransport and discarding them.
    // - No capture implementation.
    // - No real devices touched.
    class HeadlessAudioDeviceModule : public webrtc::AudioDeviceModule
    {
        public:
            static webrtc::scoped_refptr<HeadlessAudioDeviceModule> Create(
                    const webrtc::Environment & env,
                    int sample_rate_hz = 48000,
                    size_t channels = 1,
                    int bits_per_sample = 16)
            {
                return webrtc::make_ref_counted<HeadlessAudioDeviceModule>(
                        env, sample_rate_hz, channels, bits_per_sample);
            }

            HeadlessAudioDeviceModule(const webrtc::Environment & env, int sample_rate_hz, size_t channels, int bits_per_sample);
            ~HeadlessAudioDeviceModule() override;

            // ----- AudioDeviceModule interface -----
            int32_t ActiveAudioLayer(webrtc::AudioDeviceModule::AudioLayer * audioLayer) const override;
            int32_t RegisterAudioCallback(webrtc::AudioTransport * audioCallback) override;
            int32_t Init() override;
            int32_t Terminate() override;
            bool Initialized() const override;

            // --- Device enumeration (stubbed; 1 virtual output device) ---
            int16_t PlayoutDevices() override;
            int16_t RecordingDevices() override;
            int32_t PlayoutDeviceName(uint16_t index,
                                      char name[webrtc::kAdmMaxDeviceNameSize],
                                      char guid[webrtc::kAdmMaxGuidSize]) override;
            int32_t RecordingDeviceName(uint16_t index,
                                        char name[webrtc::kAdmMaxDeviceNameSize],
                                        char guid[webrtc::kAdmMaxGuidSize]) override;

            // --- Device selection (recording not supported) ---
            int32_t SetPlayoutDevice(uint16_t index) override;
            int32_t SetPlayoutDevice(WindowsDeviceType device) override;
            int32_t SetRecordingDevice(uint16_t index) override;
            int32_t SetRecordingDevice(WindowsDeviceType device) override;

            // --- Audio transport initialization ---
            int32_t PlayoutIsAvailable(bool * available) override;
            int32_t InitPlayout() override;
            bool PlayoutIsInitialized() const override;
            int32_t RecordingIsAvailable(bool * available) override;
            int32_t InitRecording() override;
            bool RecordingIsInitialized() const override;

            // --- Audio transport control (playout only) ---
            int32_t StartPlayout() override;
            int32_t StopPlayout() override;
            bool Playing() const override;
            int32_t StartRecording() override;
            int32_t StopRecording() override;
            bool Recording() const override;

            // --- Mixer init (report success; nothing to init physically) ---
            int32_t InitSpeaker() override;
            bool SpeakerIsInitialized() const override;
            int32_t InitMicrophone() override;
            bool MicrophoneIsInitialized() const override;

            // --- Speaker volume (not supported) ---
            int32_t SpeakerVolumeIsAvailable(bool * available) override;
            int32_t SetSpeakerVolume(uint32_t volume) override;
            int32_t SpeakerVolume(uint32_t * volume) const override;
            int32_t MaxSpeakerVolume(uint32_t * maxVolume) const override;
            int32_t MinSpeakerVolume(uint32_t * minVolume) const override;

            // --- Microphone volume (not supported) ---
            int32_t MicrophoneVolumeIsAvailable(bool * available) override;
            int32_t SetMicrophoneVolume(uint32_t volume) override;
            int32_t MicrophoneVolume(uint32_t * volume) const override;
            int32_t MaxMicrophoneVolume(uint32_t * maxVolume) const override;
            int32_t MinMicrophoneVolume(uint32_t * minVolume) const override;

            // --- Mute controls (not supported) ---
            int32_t SpeakerMuteIsAvailable(bool * available) override;
            int32_t SetSpeakerMute(bool enable) override;
            int32_t SpeakerMute(bool * enabled) const override;
            int32_t MicrophoneMuteIsAvailable(bool * available) override;
            int32_t SetMicrophoneMute(bool enable) override;
            int32_t MicrophoneMute(bool * enabled) const override;

            // --- Stereo support (playout only) ---
            int32_t StereoPlayoutIsAvailable(bool * available) const override;
            int32_t SetStereoPlayout(bool enable) override;
            int32_t StereoPlayout(bool * enabled) const override;
            int32_t StereoRecordingIsAvailable(bool * available) const override;
            int32_t SetStereoRecording(bool enable) override;
            int32_t StereoRecording(bool * enabled) const override;

            // --- Playout delay (fixed, nominal) ---
            int32_t PlayoutDelay(uint16_t * delayMS) const override;

            // --- Built-in effects (not supported here) ---
            bool BuiltInAECIsAvailable() const override;
            bool BuiltInAGCIsAvailable() const override;
            bool BuiltInNSIsAvailable() const override;
            int32_t EnableBuiltInAEC(bool enable) override;
            int32_t EnableBuiltInAGC(bool enable) override;
            int32_t EnableBuiltInNS(bool enable) override;

            // Android-only in real ADMs; just return 0 here.
            int32_t GetPlayoutUnderrunCount() const override;

        private:
            bool PlayThreadProcess();

            // State
            bool initialized_ = false;
            bool playout_initialized_ = false;
            bool playing_ = false;

            // Format
            int sample_rate_hz_ = 48000;
            size_t channels_ = 1;

            webrtc::BufferT<int16_t> play_buffer_;

            size_t playoutFramesIn10MS_;
            int64_t lastCallPlayoutMillis_;

            mutable webrtc::Mutex mutex_;
            std::unique_ptr<webrtc::AudioDeviceBuffer> audio_device_buffer_ RTC_GUARDED_BY(mutex_);

            webrtc::PlatformThread render_thread_;
    };
}

#endif