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

#include "api/ToneAudioDeviceModule.h"

#include "rtc_base/time_utils.h"

#include <array>
#include <chrono>
#include <cmath>
#include <thread>

namespace jni
{
	ToneAudioDeviceModule::ToneAudioDeviceModule(int frequencyHz) :
		HeadlessAudioDeviceModule(kSampleRate, 1),
		frequencyHz_(frequencyHz)
	{
	}

	ToneAudioDeviceModule::~ToneAudioDeviceModule()
	{
		// The base destructor stops only its own recording state.
		ToneAudioDeviceModule::StopRecording();
	}

	int32_t ToneAudioDeviceModule::RegisterAudioCallback(webrtc::AudioTransport * audioCallback)
	{
		{
			// Waits for a recording callback in progress, as the base class
			// waits for a pull, so the old transport is not called afterwards.
			webrtc::MutexLock lock(&recordMutex_);

			recordCallback_ = audioCallback;
		}

		return HeadlessAudioDeviceModule::RegisterAudioCallback(audioCallback);
	}

	int32_t ToneAudioDeviceModule::Terminate()
	{
		ToneAudioDeviceModule::StopRecording();

		return HeadlessAudioDeviceModule::Terminate();
	}

	int32_t ToneAudioDeviceModule::StartRecording()
	{
		int32_t result = HeadlessAudioDeviceModule::StartRecording();

		if (result != 0) {
			return result;
		}

		{
			webrtc::MutexLock lock(&recordMutex_);

			if (recordRunning_) {
				return 0;
			}

			recordRunning_ = true;
		}

		nextRecordMillis_ = webrtc::TimeMillis();
		recordThread_ = webrtc::PlatformThread::SpawnJoinable(
			[this] {
				while (RecordThreadProcess()) {
				}
			},
			"webrtc_tone_audio_module_record_thread",
			webrtc::ThreadAttributes().SetPriority(webrtc::ThreadPriority::kRealtime));

		return 0;
	}

	int32_t ToneAudioDeviceModule::StopRecording()
	{
		{
			webrtc::MutexLock lock(&recordMutex_);

			recordRunning_ = false;
		}

		if (!recordThread_.empty()) {
			recordThread_.Finalize();
		}

		return HeadlessAudioDeviceModule::StopRecording();
	}

	bool ToneAudioDeviceModule::RecordThreadProcess()
	{
		std::array<int16_t, kFramesPer10Ms> samples;

		for (size_t i = 0; i < kFramesPer10Ms; ++i, ++phase_) {
			const double angle = 2 * 3.14159265358979323846 * frequencyHz_ * static_cast<double>(phase_) / kSampleRate;

			samples[i] = static_cast<int16_t>(8000 * std::sin(angle));
		}

		{
			webrtc::MutexLock lock(&recordMutex_);

			if (!recordRunning_) {
				return false;
			}

			if (recordCallback_ != nullptr) {
				uint32_t newMicLevel = 0;

				recordCallback_->RecordedDataIsAvailable(samples.data(), kFramesPer10Ms, sizeof(int16_t), 1,
					kSampleRate, 0, 0, 0, false, newMicLevel, webrtc::TimeNanos());
			}
		}

		nextRecordMillis_ += 10;

		const int64_t waitMillis = nextRecordMillis_ - webrtc::TimeMillis();

		if (waitMillis > 0) {
			std::this_thread::sleep_for(std::chrono::milliseconds(waitMillis));
		}

		return true;
	}
}
