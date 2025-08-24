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
                                                         size_t channels):
            sample_rate_hz_(sample_rate_hz),
            channels_(channels ? channels : 1),
            playoutFramesIn10MS_(0),
            recordingFramesIn10MS_(0),
            lastCallPlayoutMillis_(0),
            lastCallRecordMillis_(0),
            audio_callback_(nullptr)
    {
        audio_device_buffer_ = std::make_unique<webrtc::AudioDeviceBuffer>(&env.task_queue_factory());
    }

    HeadlessAudioDeviceModule::~HeadlessAudioDeviceModule()
    {
        StopPlayout();
        StopRecording();
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
        audio_callback_ = audioCallback;
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
        return 1;
    }

    int32_t HeadlessAudioDeviceModule::PlayoutDeviceName(uint16_t index,
                                                         char name[webrtc::kAdmMaxDeviceNameSize],
                                                         char guid[webrtc::kAdmMaxGuidSize])
    {
        if (index != 0) {
            return -1;
        }

        std::snprintf(name, webrtc::kAdmMaxDeviceNameSize, "Virtual Speaker (ADM)");
        std::snprintf(guid, webrtc::kAdmMaxGuidSize, "virtual-speaker");

        return 0;
    }

    int32_t HeadlessAudioDeviceModule::RecordingDeviceName(uint16_t index,
                                                           char name[webrtc::kAdmMaxDeviceNameSize],
                                                           char guid[webrtc::kAdmMaxGuidSize])
    {
        if (index != 0) {
            return -1;
        }
        std::snprintf(name, webrtc::kAdmMaxDeviceNameSize, "Virtual Microphone (ADM)");
        std::snprintf(guid, webrtc::kAdmMaxGuidSize, "virtual-microphone");
        return 0;
    }

    int32_t HeadlessAudioDeviceModule::SetPlayoutDevice(uint16_t index)
    {
        return (index == 0) ? 0 : -1;
    }

    int32_t HeadlessAudioDeviceModule::SetPlayoutDevice(WindowsDeviceType /*device*/)
    {
        return 0;
    }

    int32_t HeadlessAudioDeviceModule::SetRecordingDevice(uint16_t index)
    {
        return (index == 0) ? 0 : -1;
    }

    int32_t HeadlessAudioDeviceModule::SetRecordingDevice(WindowsDeviceType /*device*/)
    {
        return 0;
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
        *available = true;
        return 0;
    }

    int32_t HeadlessAudioDeviceModule::InitRecording()
    {
        if (!initialized_) {
            return -1;
        }

        recordingFramesIn10MS_ = static_cast<size_t>(sample_rate_hz_ / 100);

        audio_device_buffer_->SetRecordingSampleRate(static_cast<uint32_t>(sample_rate_hz_));
        audio_device_buffer_->SetRecordingChannels(static_cast<int>(channels_));

        const size_t total_samples = channels_ * recordingFramesIn10MS_;
        if (record_buffer_.size() != total_samples) {
            record_buffer_.SetSize(total_samples);
        }

        recording_initialized_ = true;
        return 0;
    }

    bool HeadlessAudioDeviceModule::RecordingIsInitialized() const
    {
        return recording_initialized_;
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
        if (!initialized_) {
            return -1;
        }
        if (!recording_initialized_) {
            return -1;
        }

        {
            webrtc::MutexLock lock(&mutex_);
            if (recording_) {
                return 0; // already recording
            }
            recording_ = true;
            audio_device_buffer_->StartRecording();
        }

        // Launch 10ms capture push thread.
        capture_thread_ = webrtc::PlatformThread::SpawnJoinable(
            [this] {
                while (CaptureThreadProcess()) {
                }
            },
            "webrtc_audio_module_capture_thread",
            webrtc::ThreadAttributes().SetPriority(webrtc::ThreadPriority::kRealtime));

        return 0;
    }

    int32_t HeadlessAudioDeviceModule::StopRecording()
    {
        {
            webrtc::MutexLock lock(&mutex_);
            if (!recording_) {
                // Already stopped.
                return 0;
            }
            recording_ = false;
        }

        if (!capture_thread_.empty()) {
            capture_thread_.Finalize();
        }

        {
            webrtc::MutexLock lock(&mutex_);
            audio_device_buffer_->StopRecording();
        }
        return 0;
    }

    bool HeadlessAudioDeviceModule::Recording() const
    {
        return recording_;
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
        return 0;
    }

    bool HeadlessAudioDeviceModule::MicrophoneIsInitialized() const
    {
        return true;
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
        *available = (channels_ >= 2);
        return 0;
    }

    int32_t HeadlessAudioDeviceModule::SetStereoRecording(bool enable)
    {
        if (recording_initialized_) {
            return -1;
        }
        channels_ = enable ? 2u : 1u;
        webrtc::MutexLock lock(&mutex_);
        const size_t total_samples = channels_ * recordingFramesIn10MS_;
        if (record_buffer_.size() != total_samples) {
            record_buffer_.SetSize(total_samples);
        }
        audio_device_buffer_->SetRecordingChannels(static_cast<int>(channels_));
        return 0;
    }

    int32_t HeadlessAudioDeviceModule::StereoRecording(bool * enabled) const
    {
        if (!enabled) {
            return -1;
        }
        *enabled = (channels_ >= 2);
        return 0;
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

    bool HeadlessAudioDeviceModule::CaptureThreadProcess()
    {
        webrtc::AudioTransport* callback = nullptr;
        {
            webrtc::MutexLock lock(&mutex_);
            if (!recording_) {
                return false;
            }
            callback = audio_callback_;
        }

        int64_t currentTime = webrtc::TimeMillis();
        mutex_.Lock();

        if (lastCallRecordMillis_ == 0 || currentTime - lastCallRecordMillis_ >= 10) {
            size_t nSamplesOut = 0;
            const size_t nBytesPerSample = sizeof(int16_t);
            const size_t nChannels = channels_;
            const uint32_t samplesPerSec = static_cast<uint32_t>(sample_rate_hz_);
            int64_t elapsed_time_ms = 0;
            int64_t ntp_time_ms = 0;

            if (callback) {
                // Pull 10 ms of audio from the registered AudioTransport (Java AudioSource).
                callback->NeedMorePlayData(recordingFramesIn10MS_ * nChannels,
                                           nBytesPerSample,
                                           nChannels,
                                           samplesPerSec,
                                           record_buffer_.data(),
                                           nSamplesOut,
                                           &elapsed_time_ms,
                                           &ntp_time_ms);
            }
            else {
                nSamplesOut = recordingFramesIn10MS_ * nChannels;
                std::memset(record_buffer_.data(), 0, nSamplesOut * nBytesPerSample);
            }

            if (nChannels > 0) {
                // Feed the captured buffer to WebRTC.
                audio_device_buffer_->SetRecordedBuffer(record_buffer_.data(), recordingFramesIn10MS_);
                audio_device_buffer_->SetVQEData(/*play_delay_ms*/ 0, /*rec_delay_ms*/ 0);

                lastCallRecordMillis_ = currentTime;

                mutex_.Unlock();
                audio_device_buffer_->DeliverRecordedData();
                mutex_.Lock();
            }
        }

        mutex_.Unlock();

        int64_t deltaTimeMillis = webrtc::TimeMillis() - currentTime;
        if (deltaTimeMillis < 10) {
            webrtc::Thread::SleepMs(10 - deltaTimeMillis);
        }

        return true;
    }
}
