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

#include "api/HeadlessAudioDeviceModule.h"

namespace jni
{
    HeadlessAudioDeviceModule::HeadlessAudioDeviceModule(const webrtc::Environment & env,
                                                         int sample_rate_hz,
                                                         size_t channels,
                                                         int /*bits_per_sample*/):
            sample_rate_hz_(sample_rate_hz),
            channels_(channels ? channels : 1),
            playoutFramesIn10MS_(0),
            lastCallPlayoutMillis_(0)
    {
        audio_device_buffer_ = std::make_unique<webrtc::AudioDeviceBuffer>(&env.task_queue_factory(), true);
        Init();
    }

    HeadlessAudioDeviceModule::~HeadlessAudioDeviceModule()
    {
        StopPlayout();
    }

    int32_t HeadlessAudioDeviceModule::ActiveAudioLayer(webrtc::AudioDeviceModule::AudioLayer * audioLayer) const
    {
        if (!audioLayer) {
            return -1;
        }
        *audioLayer = kDummyAudio;
        return 0;
    }

    int32_t HeadlessAudioDeviceModule::RegisterAudioCallback(webrtc::AudioTransport * audioCallback)
    {
        webrtc::MutexLock lock(&mutex_);
        audio_device_buffer_->RegisterAudioCallback(audioCallback);
        return 0;
    }

    int32_t HeadlessAudioDeviceModule::Init()
    {
        initialized_ = true;
        return 0;
    }

    int32_t HeadlessAudioDeviceModule::Terminate()
    {
        StopPlayout();
        initialized_ = false;
        return 0;
    }

    bool HeadlessAudioDeviceModule::Initialized() const
    {
        return initialized_;
    }

    int16_t HeadlessAudioDeviceModule::PlayoutDevices()
    {
        return 1;
    }

    int16_t HeadlessAudioDeviceModule::RecordingDevices()
    {
        return 0;
    }

    int32_t HeadlessAudioDeviceModule::PlayoutDeviceName(uint16_t index,
                                                         char name[webrtc::kAdmMaxDeviceNameSize],
                                                         char guid[webrtc::kAdmMaxGuidSize])
    {
        if (index != 0) {
            return -1;
        }

        std::snprintf(name, webrtc::kAdmMaxDeviceNameSize, "Discard Playout (ADM)");
        std::snprintf(guid, webrtc::kAdmMaxGuidSize, "discard-playout");

        return 0;
    }

    int32_t HeadlessAudioDeviceModule::RecordingDeviceName(uint16_t /*index*/,
                                                           char name[webrtc::kAdmMaxDeviceNameSize],
                                                           char guid[webrtc::kAdmMaxGuidSize])
    {
        if (name) {
            name[0] = '\0';
        }
        if (guid) {
            guid[0] = '\0';
        }

        return -1; // no recording devices
    }

    int32_t HeadlessAudioDeviceModule::SetPlayoutDevice(uint16_t index)
    {
        return (index == 0) ? 0 : -1;
    }

    int32_t HeadlessAudioDeviceModule::SetPlayoutDevice(WindowsDeviceType /*device*/)
    {
        return 0;
    }

    int32_t HeadlessAudioDeviceModule::SetRecordingDevice(uint16_t /*index*/)
    {
        return -1;
    }

    int32_t HeadlessAudioDeviceModule::SetRecordingDevice(WindowsDeviceType /*device*/)
    {
        return -1;
    }

    int32_t HeadlessAudioDeviceModule::PlayoutIsAvailable(bool * available)
    {
        if (!available) {
            return -1;
        }
        *available = true;
        return 0;
    }

    int32_t HeadlessAudioDeviceModule::InitPlayout()
    {
        if (!initialized_) {
            return -1;
        }

        playoutFramesIn10MS_ = static_cast<size_t>(sample_rate_hz_ / 100);

        audio_device_buffer_->SetPlayoutSampleRate(static_cast<uint32_t>(sample_rate_hz_));
        audio_device_buffer_->SetPlayoutChannels(static_cast<int>(channels_));

        const size_t total_samples = channels_ * playoutFramesIn10MS_;
        if (play_buffer_.size() != total_samples) {
            play_buffer_.SetSize(total_samples);
        }

        playout_initialized_ = true;
        return 0;
    }

    bool HeadlessAudioDeviceModule::PlayoutIsInitialized() const
    {
        return playout_initialized_;
    }

    int32_t HeadlessAudioDeviceModule::RecordingIsAvailable(bool * available)
    {
        if (!available) {
            return -1;
        }
        *available = false;
        return 0;
    }

    int32_t HeadlessAudioDeviceModule::InitRecording()
    {
        return -1;
    }

    bool HeadlessAudioDeviceModule::RecordingIsInitialized() const
    {
        return false;
    }

    int32_t HeadlessAudioDeviceModule::StartPlayout()
    {
        if (!initialized_) {
            return -1;
        }
        if (!playout_initialized_) {
            return -1;
        }

        {
            webrtc::MutexLock lock(&mutex_);
            if (playing_) {
                return 0; // already playing
            }
            playing_ = true;
            audio_device_buffer_->StartPlayout();
        }

        // Launch 10ms render pull thread.
        render_thread_ = webrtc::PlatformThread::SpawnJoinable(
            [this] {
                while (PlayThreadProcess()) {
                }
            },
            "webrtc_audio_module_play_thread",
            webrtc::ThreadAttributes().SetPriority(webrtc::ThreadPriority::kRealtime));

        return 0;
    }

    int32_t HeadlessAudioDeviceModule::StopPlayout()
    {
        {
            webrtc::MutexLock lock(&mutex_);
            if (!playing_) {
                // Already stopped.
                return 0;
            }
            playing_ = false;
        }

        if (!render_thread_.empty()) {
            render_thread_.Finalize();
        }

        {
            webrtc::MutexLock lock(&mutex_);
            audio_device_buffer_->StopPlayout();
        }
        return 0;
    }

    bool HeadlessAudioDeviceModule::Playing() const
    {
        return playing_;
    }

    int32_t HeadlessAudioDeviceModule::StartRecording()
    {
        return -1;
    }

    int32_t HeadlessAudioDeviceModule::StopRecording()
    {
        return 0;
    }

    bool HeadlessAudioDeviceModule::Recording() const
    {
        return false;
    }

    int32_t HeadlessAudioDeviceModule::InitSpeaker()
    {
        return 0;
    }

    bool HeadlessAudioDeviceModule::SpeakerIsInitialized() const
    {
        return true;
    }

    int32_t HeadlessAudioDeviceModule::InitMicrophone()
    {
        return -1;
    }

    bool HeadlessAudioDeviceModule::MicrophoneIsInitialized() const
    {
        return false;
    }

    int32_t HeadlessAudioDeviceModule::SpeakerVolumeIsAvailable(bool * available)
    {
        if (!available) {
            return -1;
        }
        *available = false;
        return 0;
    }

    int32_t HeadlessAudioDeviceModule::SetSpeakerVolume(uint32_t /*volume*/)
    {
        return -1;
    }

    int32_t HeadlessAudioDeviceModule::SpeakerVolume(uint32_t * /*volume*/) const
    {
        return -1;
    }

    int32_t HeadlessAudioDeviceModule::MaxSpeakerVolume(uint32_t * /*maxVolume*/) const
    {
        return -1;
    }

    int32_t HeadlessAudioDeviceModule::MinSpeakerVolume(uint32_t * /*minVolume*/) const
    {
        return -1;
    }

    int32_t HeadlessAudioDeviceModule::MicrophoneVolumeIsAvailable(bool * available)
    {
        if (!available) {
            return -1;
        }
        *available = false;
        return 0;
    }

    int32_t HeadlessAudioDeviceModule::SetMicrophoneVolume(uint32_t /*volume*/)
    {
        return -1;
    }

    int32_t HeadlessAudioDeviceModule::MicrophoneVolume(uint32_t * /*volume*/) const
    {
        return -1;
    }

    int32_t HeadlessAudioDeviceModule::MaxMicrophoneVolume(uint32_t * /*maxVolume*/) const
    {
        return -1;
    }

    int32_t HeadlessAudioDeviceModule::MinMicrophoneVolume(uint32_t * /*minVolume*/) const
    {
        return -1;
    }

    int32_t HeadlessAudioDeviceModule::SpeakerMuteIsAvailable(bool * available)
    {
        if (!available) {
            return -1;
        }
        *available = false;
        return 0;
    }

    int32_t HeadlessAudioDeviceModule::SetSpeakerMute(bool /*enable*/)
    {
        return -1;
    }

    int32_t HeadlessAudioDeviceModule::SpeakerMute(bool * /*enabled*/) const
    {
        return -1;
    }

    int32_t HeadlessAudioDeviceModule::MicrophoneMuteIsAvailable(bool * available)
    {
        if (!available) {
            return -1;
        }
        *available = false;
        return 0;
    }

    int32_t HeadlessAudioDeviceModule::SetMicrophoneMute(bool /*enable*/)
    {
        return -1;
    }

    int32_t HeadlessAudioDeviceModule::MicrophoneMute(bool * /*enabled*/) const
    {
        return -1;
    }

    int32_t HeadlessAudioDeviceModule::StereoPlayoutIsAvailable(bool * available) const
    {
        if (!available) {
            return -1;
        }
        *available = (channels_ >= 2);
        return 0;
    }

    int32_t HeadlessAudioDeviceModule::SetStereoPlayout(bool enable)
    {
        if (playout_initialized_) {
            return -1;
        }

        channels_ = enable ? 2u : 1u;
        // Propagate channel change to AudioDeviceBuffer if playout is initialized.
        webrtc::MutexLock lock(&mutex_);

        const size_t total_samples = channels_ * playoutFramesIn10MS_;
        if (play_buffer_.size() != total_samples) {
            play_buffer_.SetSize(total_samples);
        }

        audio_device_buffer_->SetPlayoutChannels(static_cast<int>(channels_));
        return 0;
    }

    int32_t HeadlessAudioDeviceModule::StereoPlayout(bool * enabled) const
    {
        if (!enabled) {
            return -1;
        }
        *enabled = (channels_ >= 2);
        return 0;
    }

    int32_t HeadlessAudioDeviceModule::StereoRecordingIsAvailable(bool * available) const
    {
        if (!available) {
            return -1;
        }
        *available = false;
        return 0;
    }

    int32_t HeadlessAudioDeviceModule::SetStereoRecording(bool /*enable*/)
    {
        return -1;
    }

    int32_t HeadlessAudioDeviceModule::StereoRecording(bool * /*enabled*/) const
    {
        return -1;
    }

    int32_t HeadlessAudioDeviceModule::PlayoutDelay(uint16_t * delayMS) const
    {
        if (!delayMS) {
            return -1;
        }
        *delayMS = 50; // arbitrary nominal
        return 0;
    }

    bool HeadlessAudioDeviceModule::BuiltInAECIsAvailable() const
    {
        return false;
    }

    bool HeadlessAudioDeviceModule::BuiltInAGCIsAvailable() const
    {
        return false;
    }

    bool HeadlessAudioDeviceModule::BuiltInNSIsAvailable() const
    {
        return false;
    }

    int32_t HeadlessAudioDeviceModule::EnableBuiltInAEC(bool /*enable*/)
    {
        return -1;
    }

    int32_t HeadlessAudioDeviceModule::EnableBuiltInAGC(bool /*enable*/)
    {
        return -1;
    }

    int32_t HeadlessAudioDeviceModule::EnableBuiltInNS(bool /*enable*/)
    {
        return -1;
    }

    int32_t HeadlessAudioDeviceModule::GetPlayoutUnderrunCount() const
    {
        return 0;
    }

    bool HeadlessAudioDeviceModule::PlayThreadProcess()
    {
        {
            webrtc::MutexLock lock(&mutex_);
            if (!playing_) {
                return false;
            }
        }

        int64_t currentTime = webrtc::TimeMillis();
        mutex_.Lock();

        if (lastCallPlayoutMillis_ == 0 || currentTime - lastCallPlayoutMillis_ >= 10) {
            mutex_.Unlock();
            audio_device_buffer_->RequestPlayoutData(playoutFramesIn10MS_);
            mutex_.Lock();

            audio_device_buffer_->GetPlayoutData(play_buffer_.data());

            lastCallPlayoutMillis_ = currentTime;
        }

        mutex_.Unlock();

        int64_t deltaTimeMillis = webrtc::TimeMillis() - currentTime;

        if (deltaTimeMillis < 10) {
            webrtc::Thread::SleepMs(10 - deltaTimeMillis);
        }

        return true;
    }
}
