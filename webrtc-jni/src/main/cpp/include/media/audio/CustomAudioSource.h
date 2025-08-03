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

#ifndef JNI_WEBRTC_API_AUDIO_CUSTOM_AUDIO_SOURCE_H_
#define JNI_WEBRTC_API_AUDIO_CUSTOM_AUDIO_SOURCE_H_

#include "api/media_stream_interface.h"
#include "rtc_base/ref_counted_object.h"

#include "media/SyncClock.h"

#include <memory>
#include <vector>
#include <atomic>

namespace jni
{
    class CustomAudioSource : public webrtc::AudioSourceInterface
    {
        public:
            explicit CustomAudioSource(std::shared_ptr<SyncClock> clock);

            // AudioSourceInterface implementation
            void RegisterObserver(webrtc::ObserverInterface * observer) override;
            void UnregisterObserver(webrtc::ObserverInterface * observer) override;
            void AddSink(webrtc::AudioTrackSinkInterface * sink) override;
            void RemoveSink(webrtc::AudioTrackSinkInterface * sink) override;
            SourceState state() const override;
            bool remote() const override;

            // Push audio data with synchronization
            void PushAudioData(const void * audio_data, int bits_per_sample,
                              int sample_rate, size_t number_of_channels,
                              size_t number_of_frames);

            // Set audio capture delay for synchronization adjustment
            void SetAudioCaptureDelay(int64_t delay_us);

        private:
            std::vector<webrtc::AudioTrackSinkInterface*> sinks_;
            std::shared_ptr<SyncClock> clock_;
            //webrtc::CriticalSection crit_;
            std::atomic<int64_t> total_samples_captured_;
            int64_t audio_capture_delay_us_;
    };
}

#endif