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

#ifndef JNI_WEBRTC_API_TONE_AUDIO_DEVICE_MODULE_H_
#define JNI_WEBRTC_API_TONE_AUDIO_DEVICE_MODULE_H_

#include "api/HeadlessAudioDeviceModule.h"

#include "rtc_base/platform_thread.h"
#include "rtc_base/synchronization/mutex.h"

#include <cstdint>

namespace jni
{
	// A HeadlessAudioDeviceModule that also records: while recording, it hands
	// its transport a sine tone in real time, 10 ms at a time, the way a device
	// module hands it a microphone. Only the tests reach it, so that they can
	// send device-captured audio without audio hardware.
	class ToneAudioDeviceModule : public HeadlessAudioDeviceModule
	{
		public:
			static webrtc::scoped_refptr<ToneAudioDeviceModule> Create(int frequencyHz)
			{
				return webrtc::make_ref_counted<ToneAudioDeviceModule>(frequencyHz);
			}

			explicit ToneAudioDeviceModule(int frequencyHz);
			~ToneAudioDeviceModule() override;

			int32_t RegisterAudioCallback(webrtc::AudioTransport * audioCallback) override;
			int32_t Terminate() override;
			int32_t StartRecording() override;
			int32_t StopRecording() override;

		private:
			static constexpr int kSampleRate = 48000;
			static constexpr size_t kFramesPer10Ms = kSampleRate / 100;

			// Records 10 ms; returns false once recording stops.
			bool RecordThreadProcess();

			const int frequencyHz_;

			webrtc::Mutex recordMutex_;
			bool recordRunning_ RTC_GUARDED_BY(recordMutex_) = false;
			webrtc::AudioTransport * recordCallback_ RTC_GUARDED_BY(recordMutex_) = nullptr;
			int64_t nextRecordMillis_ = 0;
			uint64_t phase_ = 0;

			webrtc::PlatformThread recordThread_;
	};
}

#endif
