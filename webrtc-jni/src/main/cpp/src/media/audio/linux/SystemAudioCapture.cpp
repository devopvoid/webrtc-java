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
#include <thread>

namespace jni {
namespace {
class PulseCapture final : public SystemAudioCapture {
    avdev::PulseAudioLoader & pa = avdev::PulseAudioLoader::instance();
    pa_threaded_mainloop * loop = nullptr;
    pa_context * context = nullptr;
    pa_stream * stream = nullptr;
    bool started = false;
    std::string sink, monitor;
    bool infoDone = false;
    struct Lock {
        PulseCapture & self;
        explicit Lock(PulseCapture & s) : self(s) { self.pa.pa_threaded_mainloop_lock(self.loop); }
        ~Lock() { self.pa.pa_threaded_mainloop_unlock(self.loop); }
    };
    template<class Test> void Await(Test test) {
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(10);
        for (;;) {
            {
                Lock lock(*this);
                if (!PA_CONTEXT_IS_GOOD(pa.pa_context_get_state(context)))
                    throw Exception("System audio: PulseAudio connection lost");
                if (test()) return;
            }
            if (std::chrono::steady_clock::now() >= deadline)
                throw Exception("System audio: PulseAudio startup timed out");
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }
    }
public:
    ~PulseCapture() override {
        if (loop && started) {
            {
                Lock lock(*this);
                if (stream) { pa.pa_stream_disconnect(stream); pa.pa_stream_unref(stream); }
                if (context) { pa.pa_context_disconnect(context); pa.pa_context_unref(context); }
            }
            pa.pa_threaded_mainloop_stop(loop);
        }
        if (loop) pa.pa_threaded_mainloop_free(loop);
    }
    void Open() override {
        if (!pa.load()) throw Exception("System audio requires libpulse.so.0");
        loop = pa.pa_threaded_mainloop_new();
        if (!loop) throw Exception("System audio: cannot create PulseAudio mainloop");
        if (pa.pa_threaded_mainloop_start(loop) < 0)
            throw Exception("System audio: cannot start PulseAudio mainloop");
        started = true;
        {
            Lock lock(*this);
            context = pa.pa_context_new(pa.pa_threaded_mainloop_get_api(loop), "webrtc-java system audio");
            if (!context || pa.pa_context_connect(context, nullptr, PA_CONTEXT_NOAUTOSPAWN, nullptr) < 0)
                throw Exception("System audio: cannot connect to PulseAudio / pipewire-pulse");
        }
        Await([&] { return pa.pa_context_get_state(context) == PA_CONTEXT_READY; });
        {
            Lock lock(*this);
            auto * op = pa.pa_context_get_server_info(context,
                [](pa_context *, const pa_server_info * info, void * data) noexcept {
                    auto * self = static_cast<PulseCapture *>(data);
                    try { if (info && info->default_sink_name) self->sink = info->default_sink_name; }
                    catch (...) { self->Fail("System audio: cannot read default sink"); }
                    self->infoDone = true;
                }, this);
            if (!op) throw Exception("System audio: cannot query default output");
            pa.pa_operation_unref(op);
        }
        Await([&] { return infoDone; });
        {
            Lock lock(*this);
            if (sink.empty()) throw Exception("System audio: no default output");
            infoDone = false;
            auto * op = pa.pa_context_get_sink_info_by_name(context, sink.c_str(),
                [](pa_context *, const pa_sink_info * info, int end, void * data) noexcept {
                    auto * self = static_cast<PulseCapture *>(data);
                    try { if (info && info->monitor_source_name) self->monitor = info->monitor_source_name; }
                    catch (...) { self->Fail("System audio: cannot read output monitor"); }
                    if (end) self->infoDone = true;
                }, this);
            if (!op) throw Exception("System audio: cannot query output monitor");
            pa.pa_operation_unref(op);
        }
        Await([&] { return infoDone; });
        {
            Lock lock(*this);
            if (monitor.empty()) throw Exception("System audio: output has no monitor source");
            pa_sample_spec spec{PA_SAMPLE_S16NE, 48000, 2};
            stream = pa.pa_stream_new(context, "System output", &spec, nullptr);
            if (!stream) throw Exception("System audio: cannot create monitor stream");
            pa_buffer_attr attr{19200, UINT32_MAX, UINT32_MAX, UINT32_MAX, 1920};
            if (pa.pa_stream_connect_record(stream, monitor.c_str(), &attr,
                    static_cast<pa_stream_flags_t>(PA_STREAM_ADJUST_LATENCY | PA_STREAM_DONT_MOVE)) < 0)
                throw Exception("System audio: cannot open output monitor");
        }
        Await([&] {
            const auto state = pa.pa_stream_get_state(stream);
            if (!PA_STREAM_IS_GOOD(state)) throw Exception("System audio: monitor unavailable or permission denied");
            return state == PA_STREAM_READY;
        });
    }
    void Poll() override {
        Lock lock(*this);
        if (pa.pa_context_get_state(context) != PA_CONTEXT_READY || pa.pa_stream_get_state(stream) != PA_STREAM_READY)
            throw Exception("System audio: output lost; stop and restart after selecting an output");
        // A bounded drain keeps stop responsive even if an output is very busy.
        for (int i = 0; i < 10; ++i) {
            size_t available = pa.pa_stream_readable_size(stream);
            if (available == static_cast<size_t>(-1)) throw Exception("System audio: monitor read failed");
            if (!available) break;
            const void * data = nullptr;
            size_t bytes = 0;
            if (pa.pa_stream_peek(stream, &data, &bytes) < 0) throw Exception("System audio: monitor peek failed");
            if (!bytes) break;
            pa_usec_t latency = 0;
            int negative = 0;
            if (pa.pa_stream_get_latency(stream, &latency, &negative) < 0 || negative) latency = 0;
            try {
                Append(static_cast<const int16_t *>(data), bytes / 4,
                       webrtc::TimeMicros() - static_cast<int64_t>(latency));
            }
            catch (...) { pa.pa_stream_drop(stream); throw; }
            if (pa.pa_stream_drop(stream) < 0) throw Exception("System audio: monitor drop failed");
        }
    }
};
}
std::unique_ptr<SystemAudioCapture> SystemAudioCapture::Create() { return std::make_unique<PulseCapture>(); }
}
