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

#include "media/audio/SystemAudioCapture.h"
#include "Exception.h"
#include <algorithm>
#include <cstring>

namespace jni {
void SystemAudioCapture::Append(const int16_t * samples, size_t frames, int64_t time) {
    std::lock_guard<std::mutex> lock(mutex_);
    // Do not splice a partial chunk from before an output pause into new
    // audio. The gap must remain a timestamp gap on the outgoing stream.
    if (!chunks_.empty() && chunks_.back().frames < 480) {
        const auto & tail = chunks_.back();
        const int64_t expected = tail.time + tail.frames * 1000000 / 48000;
        if (time - expected > 5000 || expected - time > 5000) chunks_.pop_back();
    }
    size_t offset = 0;
    while (offset < frames) {
        if (chunks_.empty() || chunks_.back().frames == 480) {
            // Bound latency and memory if the consumer stalls. Preserve capture
            // timestamps when dropping old audio instead of replaying a backlog.
            if (chunks_.size() >= 10) chunks_.pop_front();
            chunks_.emplace_back();
            chunks_.back().time = time + offset * 1000000 / 48000;
        }
        auto & chunk = chunks_.back();
        const size_t count = std::min(frames - offset, 480 - chunk.frames);
        if (samples) {
            std::memcpy(chunk.samples.data() + chunk.frames * 2,
                        samples + offset * 2, count * 4);
        }
        else {
            std::memset(chunk.samples.data() + chunk.frames * 2, 0, count * 4);
        }
        chunk.frames += count;
        offset += count;
    }
}
void SystemAudioCapture::Fail(const char * message) noexcept {
    // Native OS callbacks must never unwind through platform code.
    try {
        std::lock_guard<std::mutex> lock(mutex_);
        failure_ = message;
    }
    catch (...) { }
}
void SystemAudioCapture::Pump(CustomAudioSource * source) {
    Poll();
    std::deque<Chunk> ready;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!failure_.empty()) throw Exception(failure_.c_str());
        while (!chunks_.empty() && chunks_.front().frames == 480) {
            ready.push_back(std::move(chunks_.front()));
            chunks_.pop_front();
        }
    }
    for (auto & chunk : ready) {
        source->PushAudioData(chunk.samples.data(), 16, 48000, 2, 480, chunk.time);
    }
}
}
