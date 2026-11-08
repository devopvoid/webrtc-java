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

#ifndef JNI_SYSTEM_AUDIO_CAPTURE_H
#define JNI_SYSTEM_AUDIO_CAPTURE_H

#include "media/audio/CustomAudioSource.h"
#include <array>
#include <deque>
#include <memory>
#include <mutex>
#include <string>

namespace jni {
// Platform callbacks only write PCM into a bounded queue; they never call Java
// or WebRTC. The owning Java worker polls and delivers complete 10 ms chunks.
class SystemAudioCapture {
public:
    virtual ~SystemAudioCapture() = default;
    virtual void Open() = 0;
    virtual void Poll() = 0;
    void Pump(CustomAudioSource * source);
    void Append(const int16_t * samples, size_t frames, int64_t timestamp_us);
    void Fail(const char * message) noexcept;
    static std::unique_ptr<SystemAudioCapture> Create();
private:
    struct Chunk {
        std::array<int16_t, 960> samples{};
        size_t frames = 0;
        int64_t time = 0;
    };
    std::mutex mutex_;
    std::deque<Chunk> chunks_;
    std::string failure_;
};
}
#endif
