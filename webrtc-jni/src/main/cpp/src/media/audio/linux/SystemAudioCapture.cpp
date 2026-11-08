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
#include "media/audio/linux/PulseAudioLoader.h"

#include "Exception.h"

#include "rtc_base/time_utils.h"

#include <chrono>
#include <string>
#include <thread>

namespace jni
{
	namespace
	{
		// The bytes of one 16-bit stereo frame.
		constexpr size_t kFrameBytes = SystemAudioCapture::kChannels * sizeof(int16_t);

		// How long each step of opening the monitor may take.
		constexpr std::chrono::seconds kStartupTimeout(10);

		// The most fragments read by one Poll(), so that stopping stays
		// responsive even while an output is very busy.
		constexpr int kMaxFragmentsPerPoll = 10;

		// Records the monitor source of the default PulseAudio sink, which
		// pipewire-pulse provides as well.
		class PulseAudioCapture final : public SystemAudioCapture
		{
			public:
				~PulseAudioCapture() override;

				void Open() override;
				void Poll() override;

			private:
				// Holds the lock of the threaded mainloop for its scope.
				class MainloopLock
				{
					public:
						explicit MainloopLock(PulseAudioCapture & capture) :
							capture_(capture)
						{
							capture_.pa_.pa_threaded_mainloop_lock(capture_.mainloop_);
						}

						~MainloopLock()
						{
							capture_.pa_.pa_threaded_mainloop_unlock(capture_.mainloop_);
						}

					private:
						PulseAudioCapture & capture_;
				};

				// Polls the condition under the mainloop lock until it holds,
				// the context fails, or the startup timeout expires.
				template <class Condition>
				void Await(Condition condition);

				avdev::PulseAudioLoader & pa_ = avdev::PulseAudioLoader::instance();
				pa_threaded_mainloop * mainloop_ = nullptr;
				pa_context * context_ = nullptr;
				pa_stream * stream_ = nullptr;
				bool mainloopStarted_ = false;

				// Written by the mainloop callbacks and read by Open(), both
				// under the mainloop lock.
				std::string sinkName_;
				std::string monitorName_;
				bool infoDone_ = false;
		};

		PulseAudioCapture::~PulseAudioCapture()
		{
			if (mainloop_ != nullptr && mainloopStarted_) {
				{
					MainloopLock lock(*this);

					if (stream_ != nullptr) {
						pa_.pa_stream_disconnect(stream_);
						pa_.pa_stream_unref(stream_);
					}
					if (context_ != nullptr) {
						pa_.pa_context_disconnect(context_);
						pa_.pa_context_unref(context_);
					}
				}

				pa_.pa_threaded_mainloop_stop(mainloop_);
			}
			if (mainloop_ != nullptr) {
				pa_.pa_threaded_mainloop_free(mainloop_);
			}
		}

		template <class Condition>
		void PulseAudioCapture::Await(Condition condition)
		{
			const auto deadline = std::chrono::steady_clock::now() + kStartupTimeout;

			while (true) {
				{
					MainloopLock lock(*this);

					if (!PA_CONTEXT_IS_GOOD(pa_.pa_context_get_state(context_))) {
						throw Exception("System audio: PulseAudio connection lost");
					}
					if (condition()) {
						return;
					}
				}

				if (std::chrono::steady_clock::now() >= deadline) {
					throw Exception("System audio: PulseAudio startup timed out");
				}

				std::this_thread::sleep_for(std::chrono::milliseconds(2));
			}
		}

		void PulseAudioCapture::Open()
		{
			if (!pa_.load()) {
				throw Exception("System audio requires libpulse.so.0");
			}

			mainloop_ = pa_.pa_threaded_mainloop_new();

			if (mainloop_ == nullptr) {
				throw Exception("System audio: Cannot create PulseAudio mainloop");
			}
			if (pa_.pa_threaded_mainloop_start(mainloop_) < 0) {
				throw Exception("System audio: Cannot start PulseAudio mainloop");
			}

			mainloopStarted_ = true;

			{
				MainloopLock lock(*this);

				context_ = pa_.pa_context_new(pa_.pa_threaded_mainloop_get_api(mainloop_), "webrtc-java system audio");

				if (context_ == nullptr || pa_.pa_context_connect(context_, nullptr, PA_CONTEXT_NOAUTOSPAWN, nullptr) < 0) {
					throw Exception("System audio: Cannot connect to PulseAudio or pipewire-pulse");
				}
			}

			Await([this]() {
				return pa_.pa_context_get_state(context_) == PA_CONTEXT_READY;
			});

			{
				MainloopLock lock(*this);

				pa_operation * op = pa_.pa_context_get_server_info(context_,
					[](pa_context *, const pa_server_info * info, void * data) noexcept {
						auto * self = static_cast<PulseAudioCapture *>(data);

						try {
							if (info != nullptr && info->default_sink_name != nullptr) {
								self->sinkName_ = info->default_sink_name;
							}
						}
						catch (...) {
							self->Fail("System audio: Cannot read the default output");
						}

						self->infoDone_ = true;
					}, this);

				if (op == nullptr) {
					throw Exception("System audio: Cannot query the default output");
				}

				pa_.pa_operation_unref(op);
			}

			Await([this]() {
				return infoDone_;
			});

			{
				MainloopLock lock(*this);

				if (sinkName_.empty()) {
					throw Exception("System audio: No default output");
				}

				infoDone_ = false;

				pa_operation * op = pa_.pa_context_get_sink_info_by_name(context_, sinkName_.c_str(),
					[](pa_context *, const pa_sink_info * info, int eol, void * data) noexcept {
						auto * self = static_cast<PulseAudioCapture *>(data);

						try {
							if (info != nullptr && info->monitor_source_name != nullptr) {
								self->monitorName_ = info->monitor_source_name;
							}
						}
						catch (...) {
							self->Fail("System audio: Cannot read the output monitor");
						}

						// Also set on an error (eol < 0), so that Open() fails
						// with no monitor rather than waiting for the timeout.
						if (eol != 0) {
							self->infoDone_ = true;
						}
					}, this);

				if (op == nullptr) {
					throw Exception("System audio: Cannot query the output monitor");
				}

				pa_.pa_operation_unref(op);
			}

			Await([this]() {
				return infoDone_;
			});

			{
				MainloopLock lock(*this);

				if (monitorName_.empty()) {
					throw Exception("System audio: The output has no monitor source");
				}

				pa_sample_spec spec { PA_SAMPLE_S16NE, kSampleRate, static_cast<uint8_t>(kChannels) };

				stream_ = pa_.pa_stream_new(context_, "System output", &spec, nullptr);

				if (stream_ == nullptr) {
					throw Exception("System audio: Cannot create the monitor stream");
				}

				// Buffer at most 100 ms, delivered in fragments of 10 ms.
				pa_buffer_attr attr {};
				attr.maxlength = static_cast<uint32_t>(10 * kFramesPerChunk * kFrameBytes);
				attr.tlength = UINT32_MAX;
				attr.prebuf = UINT32_MAX;
				attr.minreq = UINT32_MAX;
				attr.fragsize = static_cast<uint32_t>(kFramesPerChunk * kFrameBytes);

				// The timing flags keep the stream's latency up to date, which
				// dates the captured audio. Without them it is never known.
				auto flags = static_cast<pa_stream_flags_t>(PA_STREAM_ADJUST_LATENCY | PA_STREAM_DONT_MOVE |
					PA_STREAM_AUTO_TIMING_UPDATE | PA_STREAM_INTERPOLATE_TIMING);

				if (pa_.pa_stream_connect_record(stream_, monitorName_.c_str(), &attr, flags) < 0) {
					throw Exception("System audio: Cannot open the output monitor");
				}
			}

			Await([this]() {
				const pa_stream_state_t state = pa_.pa_stream_get_state(stream_);

				if (!PA_STREAM_IS_GOOD(state)) {
					throw Exception("System audio: Output monitor unavailable or permission denied");
				}

				return state == PA_STREAM_READY;
			});
		}

		void PulseAudioCapture::Poll()
		{
			MainloopLock lock(*this);

			if (pa_.pa_context_get_state(context_) != PA_CONTEXT_READY ||
				pa_.pa_stream_get_state(stream_) != PA_STREAM_READY) {
				throw Exception("System audio: Output lost; stop and restart after selecting an output");
			}

			// The fragments read by one call follow each other without a gap, so
			// all of them are dated from the latency read for the first one.
			// Dating each from a latency of its own would make them overlap.
			int64_t firstCaptureUs = 0;
			size_t framesRead = 0;

			for (int i = 0; i < kMaxFragmentsPerPoll; ++i) {
				size_t available = pa_.pa_stream_readable_size(stream_);

				if (available == static_cast<size_t>(-1)) {
					throw Exception("System audio: Monitor read failed");
				}
				if (available == 0) {
					break;
				}

				const void * data = nullptr;
				size_t bytes = 0;

				if (pa_.pa_stream_peek(stream_, &data, &bytes) < 0) {
					throw Exception("System audio: Monitor peek failed");
				}
				if (bytes == 0) {
					break;
				}

				if (framesRead == 0) {
					// The latency of a record stream is how long ago the next
					// byte to read was captured.
					pa_usec_t latency = 0;
					int negative = 0;

					if (pa_.pa_stream_get_latency(stream_, &latency, &negative) < 0 || negative) {
						latency = 0;
					}

					firstCaptureUs = webrtc::TimeMicros() - static_cast<int64_t>(latency);
				}

				const size_t frames = bytes / kFrameBytes;
				const int64_t captureUs = firstCaptureUs + static_cast<int64_t>(framesRead) * webrtc::kNumMicrosecsPerSec / kSampleRate;

				try {
					// A hole in the stream comes as null data, queued as silence.
					Append(static_cast<const int16_t *>(data), frames, captureUs);
				}
				catch (...) {
					pa_.pa_stream_drop(stream_);
					throw;
				}

				if (pa_.pa_stream_drop(stream_) < 0) {
					throw Exception("System audio: Monitor drop failed");
				}

				framesRead += frames;
			}
		}
	}

	std::unique_ptr<SystemAudioCapture> SystemAudioCapture::Create()
	{
		return std::make_unique<PulseAudioCapture>();
	}
}
