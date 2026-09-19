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
    HeadlessAudioDeviceModule::HeadlessAudioDeviceModule(int sample_rate_hz, size_t channels):
            sample_rate_hz_(sample_rate_hz),
            channels_(channels ? channels : 1),
            playoutFramesIn10MS_(0),
            nextPlayoutMillis_(0),
            audio_callback_(nullptr)
    {
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
        // The render thread holds callback_mutex_ across a pull, so this waits
        // for a pull in progress and no call reaches the old transport after
        // this returns. Unlike AudioDeviceBuffer, the swap is accepted while
        // playout runs: WebRTC registers its transport only once it builds the
        // voice engine, and an application may well have started playout by
        // then.
        webrtc::MutexLock lock(&callback_mutex_);
        audio_callback_ = audioCallback;
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

        webrtc::MutexLock lock(&mutex_);

        playoutFramesIn10MS_ = static_cast<size_t>(sample_rate_hz_ / 100);

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

        webrtc::MutexLock lock(&mutex_);

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

        // Recording is a state on this module and nothing more: there is no
        // device to capture from, and no capture thread runs. An earlier version
        // pulled the render mix through AudioTransport and handed it back as
        // recorded audio, which made a peer connection send the audio it had just
        // received from the remote peer straight back to it. Audio a headless
        // application wants to send goes through a CustomAudioSource instead.
        webrtc::MutexLock lock(&mutex_);

        recording_ = true;

        return 0;
    }

    int32_t HeadlessAudioDeviceModule::StopRecording()
    {
        webrtc::MutexLock lock(&mutex_);

        recording_ = false;

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

        webrtc::MutexLock lock(&mutex_);

        channels_ = enable ? 2u : 1u;

        const size_t total_samples = channels_ * playoutFramesIn10MS_;
        if (play_buffer_.size() != total_samples) {
            play_buffer_.SetSize(total_samples);
        }

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
        webrtc::MutexLock lock(&mutex_);

        channels_ = enable ? 2u : 1u;

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
        const int64_t currentTime = webrtc::TimeMillis();

        // Decide under the state lock whether this tick pulls, and snapshot
        // the format the pull needs, so the lock is not held during the pull.
        bool pull = false;
        size_t frames = 0;
        size_t channels = 0;
        uint32_t sampleRate = 0;
        int64_t sleepMillis = 0;

        {
            webrtc::MutexLock lock(&mutex_);

            if (!playing_) {
                return false;
            }

            // Seed the grid on the first tick.
            if (nextPlayoutMillis_ == 0) {
                nextPlayoutMillis_ = currentTime;
            }

            if (currentTime >= nextPlayoutMillis_) {
                pull = true;
                frames = playoutFramesIn10MS_;
                channels = channels_;
                sampleRate = static_cast<uint32_t>(sample_rate_hz_);

                // Advance the grid by a fixed 10 ms rather than re-anchoring to currentTime,
                // so wake-up latency is corrected on the next tick instead of accumulating
                // into the frame period (which otherwise pulls the effective rate below 100 Hz).
                nextPlayoutMillis_ += 10;

                // If we fell far behind (e.g. the thread was descheduled), resync to now
                // instead of bursting frames to catch up.
                if (nextPlayoutMillis_ < currentTime - 100) {
                    nextPlayoutMillis_ = currentTime;
                }
            }

            sleepMillis = nextPlayoutMillis_ - webrtc::TimeMillis();
        }

        if (pull) {
            // Pull 10 ms of rendered audio straight from the transport and drop
            // it; there is no device to play it on. The pull is what drives the
            // receive side of every peer connection of the factory this module
            // belongs to, so remote audio only reaches an AudioTrack sink while
            // this thread runs.
            //
            // Only callback_mutex_ is held here. It keeps the transport alive
            // for the duration of the call (see RegisterAudioCallback) without
            // serialising the pull against the rest of this module.
            webrtc::MutexLock lock(&callback_mutex_);

            if (audio_callback_) {
                size_t samplesOut = 0;
                int64_t elapsedTimeMillis = -1;
                int64_t ntpTimeMillis = -1;

                audio_callback_->NeedMorePlayData(frames,
                                                  sizeof(int16_t) * channels,
                                                  channels,
                                                  sampleRate,
                                                  play_buffer_.data(),
                                                  samplesOut,
                                                  &elapsedTimeMillis,
                                                  &ntpTimeMillis);
            }
        }

        if (sleepMillis > 0) {
            webrtc::Thread::SleepMs(sleepMillis);
        }

        return true;
    }
}
