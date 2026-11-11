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

#include <windows.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <wrl/client.h>

#include <sstream>

namespace jni
{
	namespace
	{
		using Microsoft::WRL::ComPtr;

		// The length of the shared-mode loopback buffer, 100 ms in 100 ns units.
		constexpr REFERENCE_TIME kBufferDuration = 1000000;

		// The most packets read by one Poll(), so that stopping stays
		// responsive even while an output is very busy.
		constexpr int kMaxPacketsPerPoll = 10;

		void ThrowIfFailed(HRESULT hr, const char * operation)
		{
			if (FAILED(hr)) {
				std::ostringstream message;
				message << "System audio: Cannot " << operation << " (HRESULT 0x" << std::hex
						<< static_cast<unsigned long>(hr) << "); restart capture after an output change";

				throw Exception(message.str().c_str());
			}
		}

		// Records the default multimedia output through WASAPI loopback. The
		// COM objects are created, used and released on the capture worker.
		class WasapiLoopbackCapture final : public SystemAudioCapture
		{
			public:
				~WasapiLoopbackCapture() override;

				void Open() override;
				void Poll() override;

			private:
				// Converts a packet's performance counter position, in 100 ns
				// units, to the clock of webrtc::TimeMicros().
				int64_t ToTimeMicros(UINT64 qpcPosition) const;

				bool comInitialized_ = false;
				LARGE_INTEGER qpcFrequency_ {};
				ComPtr<IMMDeviceEnumerator> enumerator_;
				ComPtr<IMMDevice> device_;
				ComPtr<IAudioClient> client_;
				ComPtr<IAudioCaptureClient> captureClient_;
		};

		WasapiLoopbackCapture::~WasapiLoopbackCapture()
		{
			if (client_) {
				client_->Stop();
			}

			captureClient_.Reset();
			client_.Reset();
			device_.Reset();
			enumerator_.Reset();

			if (comInitialized_) {
				CoUninitialize();
			}
		}

		void WasapiLoopbackCapture::Open()
		{
			ThrowIfFailed(CoInitializeEx(nullptr, COINIT_MULTITHREADED), "initialize COM");
			comInitialized_ = true;

			QueryPerformanceFrequency(&qpcFrequency_);

			ThrowIfFailed(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL,
				IID_PPV_ARGS(enumerator_.GetAddressOf())), "create the output enumerator");
			ThrowIfFailed(enumerator_->GetDefaultAudioEndpoint(eRender, eMultimedia, device_.GetAddressOf()),
				"get the default output");
			ThrowIfFailed(device_->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr,
				reinterpret_cast<void **>(client_.GetAddressOf())), "activate the output");

			WAVEFORMATEX format {};
			format.wFormatTag = WAVE_FORMAT_PCM;
			format.nChannels = static_cast<WORD>(kChannels);
			format.nSamplesPerSec = kSampleRate;
			format.wBitsPerSample = 16;
			format.nBlockAlign = static_cast<WORD>(format.nChannels * format.wBitsPerSample / 8);
			format.nAvgBytesPerSec = format.nSamplesPerSec * format.nBlockAlign;

			// The audio engine converts the mix format of the output to this one.
			ThrowIfFailed(client_->Initialize(AUDCLNT_SHAREMODE_SHARED,
				AUDCLNT_STREAMFLAGS_LOOPBACK | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM |
				AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY,
				kBufferDuration, 0, &format, nullptr), "initialize the loopback");
			ThrowIfFailed(client_->GetService(IID_PPV_ARGS(captureClient_.GetAddressOf())),
				"get the loopback capture");
			ThrowIfFailed(client_->Start(), "start the loopback");
		}

		int64_t WasapiLoopbackCapture::ToTimeMicros(UINT64 qpcPosition) const
		{
			LARGE_INTEGER now;
			QueryPerformanceCounter(&now);

			const int64_t nowUs = static_cast<int64_t>(
				static_cast<long double>(now.QuadPart) * webrtc::kNumMicrosecsPerSec / qpcFrequency_.QuadPart);

			return webrtc::TimeMicros() + static_cast<int64_t>(qpcPosition / 10) - nowUs;
		}

		void WasapiLoopbackCapture::Poll()
		{
			for (int i = 0; i < kMaxPacketsPerPoll; ++i) {
				UINT32 frames = 0;

				ThrowIfFailed(captureClient_->GetNextPacketSize(&frames), "query the output packet");

				if (frames == 0) {
					break;
				}

				BYTE * data = nullptr;
				DWORD flags = 0;
				UINT64 devicePosition = 0;
				UINT64 qpcPosition = 0;

				ThrowIfFailed(captureClient_->GetBuffer(&data, &frames, &flags, &devicePosition, &qpcPosition),
					"read the output packet");

				try {
					int64_t captureUs = (flags & AUDCLNT_BUFFERFLAGS_TIMESTAMP_ERROR)
						? webrtc::TimeMicros() - frames * webrtc::kNumMicrosecsPerSec / kSampleRate
						: ToTimeMicros(qpcPosition);
					const int16_t * samples = (flags & AUDCLNT_BUFFERFLAGS_SILENT)
						? nullptr
						: reinterpret_cast<const int16_t *>(data);

					Append(samples, frames, captureUs);
				}
				catch (...) {
					captureClient_->ReleaseBuffer(frames);
					throw;
				}

				ThrowIfFailed(captureClient_->ReleaseBuffer(frames), "release the output packet");
			}
		}
	}

	std::unique_ptr<SystemAudioCapture> SystemAudioCapture::Create()
	{
		return std::make_unique<WasapiLoopbackCapture>();
	}
}
