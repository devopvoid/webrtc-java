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

#include "rtc_base/time_utils.h"

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <utility>

namespace jni
{
	namespace
	{
		// The most chunks queued, 100 ms. When the worker falls behind, the
		// oldest audio is dropped rather than sent late as a backlog.
		constexpr size_t kMaxQueuedChunks = 10;

		// How far a packet may start from where the queued audio ends and still
		// continue it. Packet timestamps jitter by a few milliseconds; a pause
		// of the output is much longer.
		constexpr int64_t kMaxTimestampJitterUs = 10 * webrtc::kNumMicrosecsPerMillisec;

		int64_t FramesToMicros(size_t frames)
		{
			return static_cast<int64_t>(frames) * webrtc::kNumMicrosecsPerSec / SystemAudioCapture::kSampleRate;
		}
	}

	void SystemAudioCapture::Append(const int16_t * samples, size_t frames, int64_t timestamp_us)
	{
		std::lock_guard<std::mutex> lock(mutex_);

		// Do not splice a partial chunk from before an output pause into new
		// audio. The pause must remain a timestamp gap on the outgoing stream.
		if (!chunks_.empty() && chunks_.back().frames < kFramesPerChunk) {
			const Chunk & tail = chunks_.back();
			const int64_t expected_us = tail.timestamp_us + FramesToMicros(tail.frames);

			if (std::abs(timestamp_us - expected_us) > kMaxTimestampJitterUs) {
				chunks_.pop_back();
			}
		}

		size_t offset = 0;

		while (offset < frames) {
			if (chunks_.empty() || chunks_.back().frames == kFramesPerChunk) {
				// Bound latency and memory if the consumer stalls. Dropping old
				// chunks keeps the capture time of the remaining ones.
				if (chunks_.size() >= kMaxQueuedChunks) {
					chunks_.pop_front();
				}

				chunks_.emplace_back();
				chunks_.back().timestamp_us = timestamp_us + FramesToMicros(offset);
			}

			Chunk & chunk = chunks_.back();
			const size_t count = std::min(frames - offset, kFramesPerChunk - chunk.frames);
			int16_t * target = chunk.samples.data() + chunk.frames * kChannels;

			if (samples != nullptr) {
				std::memcpy(target, samples + offset * kChannels, count * kChannels * sizeof(int16_t));
			}
			else {
				std::memset(target, 0, count * kChannels * sizeof(int16_t));
			}

			chunk.frames += count;
			offset += count;
		}
	}

	void SystemAudioCapture::Fail(const char * message) noexcept
	{
		// Native OS callbacks must never unwind through platform code.
		try {
			std::lock_guard<std::mutex> lock(mutex_);

			failure_ = message;
		}
		catch (...) {
		}
	}

	void SystemAudioCapture::Pump(CustomAudioSource * source)
	{
		Poll();

		std::deque<Chunk> ready;

		{
			std::lock_guard<std::mutex> lock(mutex_);

			if (!failure_.empty()) {
				throw Exception(failure_.c_str());
			}

			while (!chunks_.empty() && chunks_.front().frames == kFramesPerChunk) {
				ready.push_back(std::move(chunks_.front()));
				chunks_.pop_front();
			}
		}

		// Deliver without the lock: the sinks may take long, and the platform
		// callbacks must not wait for them.
		for (const Chunk & chunk : ready) {
			source->PushAudioData(chunk.samples.data(), 16, kSampleRate, kChannels,
								  kFramesPerChunk, chunk.timestamp_us);
		}
	}
}
