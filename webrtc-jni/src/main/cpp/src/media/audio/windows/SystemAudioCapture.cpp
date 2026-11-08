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

namespace jni {
namespace {
using Microsoft::WRL::ComPtr;
void Check(HRESULT hr, const char * operation) {
    if (FAILED(hr)) {
        std::ostringstream message;
        message << "System audio: " << operation << " (HRESULT 0x" << std::hex
                << static_cast<unsigned long>(hr) << "); restart capture after an output change";
        throw Exception(message.str().c_str());
    }
}
class WasapiCapture final : public SystemAudioCapture {
    bool com = false;
    ComPtr<IMMDeviceEnumerator> enumerator;
    ComPtr<IMMDevice> device;
    ComPtr<IAudioClient> client;
    ComPtr<IAudioCaptureClient> capture;
public:
    ~WasapiCapture() override {
        if (client) client->Stop();
        capture.Reset(); client.Reset(); device.Reset(); enumerator.Reset();
        if (com) CoUninitialize();
    }
    void Open() override {
        Check(CoInitializeEx(nullptr, COINIT_MULTITHREADED), "initialize COM");
        com = true;
        Check(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL,
                               IID_PPV_ARGS(enumerator.GetAddressOf())), "create output enumerator");
        Check(enumerator->GetDefaultAudioEndpoint(eRender, eMultimedia, device.GetAddressOf()), "get default output");
        Check(device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr,
                              reinterpret_cast<void **>(client.GetAddressOf())), "activate output");
        WAVEFORMATEX format{};
        format.wFormatTag = WAVE_FORMAT_PCM;
        format.nChannels = 2;
        format.nSamplesPerSec = 48000;
        format.wBitsPerSample = 16;
        format.nBlockAlign = 4;
        format.nAvgBytesPerSec = 192000;
        Check(client->Initialize(AUDCLNT_SHAREMODE_SHARED,
                AUDCLNT_STREAMFLAGS_LOOPBACK | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM |
                AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY,
                1000000, 0, &format, nullptr), "initialize loopback");
        Check(client->GetService(IID_PPV_ARGS(capture.GetAddressOf())), "get loopback capture");
        Check(client->Start(), "start loopback");
    }
    void Poll() override {
        for (int i = 0; i < 10; ++i) {
            UINT32 frames = 0;
            Check(capture->GetNextPacketSize(&frames), "query output packet");
            if (!frames) break;
            BYTE * data = nullptr;
            DWORD flags = 0;
            UINT64 position = 0, qpc = 0;
            Check(capture->GetBuffer(&data, &frames, &flags, &position, &qpc), "read output packet");
            try {
                int64_t time = webrtc::TimeMicros() - frames * 1000000LL / 48000;
                if (!(flags & AUDCLNT_BUFFERFLAGS_TIMESTAMP_ERROR)) {
                    LARGE_INTEGER now, frequency;
                    QueryPerformanceCounter(&now);
                    QueryPerformanceFrequency(&frequency);
                    const int64_t host_us = static_cast<int64_t>(
                            static_cast<long double>(now.QuadPart) * 1000000 / frequency.QuadPart);
                    time = webrtc::TimeMicros() + static_cast<int64_t>(qpc / 10) - host_us;
                }
                Append(flags & AUDCLNT_BUFFERFLAGS_SILENT ? nullptr : reinterpret_cast<int16_t *>(data), frames, time);
            }
            catch (...) { capture->ReleaseBuffer(frames); throw; }
            Check(capture->ReleaseBuffer(frames), "release output packet");
        }
    }
};
}
std::unique_ptr<SystemAudioCapture> SystemAudioCapture::Create() { return std::make_unique<WasapiCapture>(); }
}
