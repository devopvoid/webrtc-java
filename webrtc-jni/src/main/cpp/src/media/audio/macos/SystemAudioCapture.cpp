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
#import <ScreenCaptureKit/ScreenCaptureKit.h>
#import <CoreMedia/CoreMedia.h>
#include <chrono>
#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <cmath>
#include <vector>

// A receiver may outlive a timed-out start/stop. Detach its owner under the
// same lock used by callbacks before destroying the native capture object.
API_AVAILABLE(macos(13.0))
@interface SystemAudioReceiver : NSObject <SCStreamOutput, SCStreamDelegate> {
@public
    jni::SystemAudioCapture * owner;
}
@end

@implementation SystemAudioReceiver
- (void)stream:(SCStream *)stream didStopWithError:(NSError *)error {
    @synchronized(self) {
        if (owner) owner->Fail("System audio: ScreenCaptureKit stopped; check recording permission and restart");
    }
}
- (void)stream:(SCStream *)stream didOutputSampleBuffer:(CMSampleBufferRef)sample ofType:(SCStreamOutputType)type {
    if (type != SCStreamOutputTypeAudio || !CMSampleBufferDataIsReady(sample)) return;
    @synchronized(self) {
        if (!owner) return;
        try {
            const auto * format = CMAudioFormatDescriptionGetStreamBasicDescription(CMSampleBufferGetFormatDescription(sample));
            if (!format || format->mFormatID != kAudioFormatLinearPCM ||
                format->mSampleRate != 48000 || format->mChannelsPerFrame != 2 ||
                !(format->mFormatFlags & kAudioFormatFlagIsFloat) || format->mBitsPerChannel != 32) {
                owner->Fail("System audio: unexpected ScreenCaptureKit PCM format");
                return;
            }
            struct { AudioBufferList list; AudioBuffer extra; } storage{};
            CMBlockBufferRef block = nullptr;
            OSStatus status = CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(sample, nullptr,
                    &storage.list, sizeof(storage), kCFAllocatorDefault, kCFAllocatorDefault,
                    kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment, &block);
            // Release the retained block even if allocating the conversion buffer fails.
            std::unique_ptr<const void, decltype(&CFRelease)> retained(block, &CFRelease);
            if (status != noErr) { owner->Fail("System audio: cannot read ScreenCaptureKit PCM"); return; }
            const size_t frames = CMSampleBufferGetNumSamples(sample);
            const bool planar = format->mFormatFlags & kAudioFormatFlagIsNonInterleaved;
            const auto & list = storage.list;
            if (list.mNumberBuffers != (planar ? 2U : 1U)) {
                owner->Fail("System audio: unexpected ScreenCaptureKit channel layout"); return;
            }
            for (UInt32 b = 0; b < list.mNumberBuffers; ++b) {
                if (!list.mBuffers[b].mData || list.mBuffers[b].mDataByteSize < frames * sizeof(float) * (planar ? 1 : 2)) {
                    owner->Fail("System audio: incomplete ScreenCaptureKit PCM"); return;
                }
            }
            std::vector<int16_t> pcm(frames * 2);
            for (size_t f = 0; f < frames; ++f) {
                for (size_t c = 0; c < 2; ++c) {
                    float value = static_cast<const float *>(list.mBuffers[planar ? c : 0].mData)[planar ? f : f * 2 + c];
                    if (!std::isfinite(value)) value = 0;
                    pcm[f * 2 + c] = static_cast<int16_t>(std::max(-32768.0f, std::min(32767.0f, value * 32768.0f)));
                }
            }
            const double pts = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample));
            const double now = CMTimeGetSeconds(CMClockGetTime(CMClockGetHostTimeClock()));
            if (!std::isfinite(pts) || !std::isfinite(now)) {
                owner->Fail("System audio: invalid ScreenCaptureKit timestamp"); return;
            }
            owner->Append(pcm.data(), frames, webrtc::TimeMicros() + static_cast<int64_t>((pts - now) * 1000000));
        }
        catch (...) { owner->Fail("System audio: ScreenCaptureKit callback failed"); }
    }
}
@end

namespace jni {
namespace {
struct Completion {
    std::mutex mutex;
    std::condition_variable condition;
    bool done = false;
    std::atomic<bool> abandoned{false};
    id keepAlive = nil;
    NSError * error = nil;
    SCShareableContent * content = nil;
    ~Completion() { [keepAlive release]; [error release]; [content release]; }
    void Finish(NSError * e, SCShareableContent * c = nil) noexcept {
        try {
            std::lock_guard<std::mutex> lock(mutex);
            error = [e retain]; content = [c retain]; done = true;
            condition.notify_all();
        }
        catch (...) { } // The waiting caller will time out; never unwind into the OS.
    }
    void Wait() {
        std::unique_lock<std::mutex> lock(mutex);
        if (!condition.wait_for(lock, std::chrono::seconds(15), [&] { return done; })) {
            abandoned = true;
            throw Exception("System audio: ScreenCaptureKit timed out; check screen/audio recording permission");
        }
        if (error) throw Exception([[error localizedDescription] UTF8String]);
    }
};
class API_AVAILABLE(macos(13.0)) ScreenAudioCapture final : public SystemAudioCapture {
    SCStream * stream = nil;
    SystemAudioReceiver * receiver = nil;
    dispatch_queue_t queue = nullptr;
public:
    ~ScreenAudioCapture() override {
        @autoreleasepool {
            if (receiver) { @synchronized(receiver) { receiver->owner = nullptr; } }
            if (stream) {
                auto completed = std::make_shared<Completion>();
                completed->keepAlive = [receiver retain];
                [stream stopCaptureWithCompletionHandler:^(NSError * error) { completed->Finish(error); }];
                try { completed->Wait(); } catch (...) { }
                [stream release];
            }
            [receiver release];
            if (queue) dispatch_release(queue);
        }
    }
    void Open() override {
        @autoreleasepool {
            auto content = std::make_shared<Completion>();
            [SCShareableContent getShareableContentExcludingDesktopWindows:YES onScreenWindowsOnly:YES
                completionHandler:^(SCShareableContent * result, NSError * error) { content->Finish(error, result); }];
            content->Wait();
            if (!content->content || content->content.displays.count == 0)
                throw Exception("System audio: no shareable display; grant screen/audio recording permission");
            SCContentFilter * filter = [[SCContentFilter alloc] initWithDisplay:content->content.displays.firstObject excludingWindows:@[]];
            SCStreamConfiguration * config = [[SCStreamConfiguration alloc] init];
            config.capturesAudio = YES;
            config.excludesCurrentProcessAudio = YES;
            config.sampleRate = 48000;
            config.channelCount = 2;
            config.width = 2; config.height = 2;
            config.minimumFrameInterval = CMTimeMake(1, 1);
            receiver = [[SystemAudioReceiver alloc] init];
            receiver->owner = this;
            queue = dispatch_queue_create("webrtc-system-audio", DISPATCH_QUEUE_SERIAL);
            stream = [[SCStream alloc] initWithFilter:filter configuration:config delegate:receiver];
            [filter release]; [config release];
            NSError * error = nil;
            if (![stream addStreamOutput:receiver type:SCStreamOutputTypeAudio sampleHandlerQueue:queue error:&error])
                throw Exception(error ? [[error localizedDescription] UTF8String] : "System audio: cannot add ScreenCaptureKit audio output");
            auto completed = std::make_shared<Completion>();
            completed->keepAlive = [receiver retain];
            SCStream * starting = stream;
            [stream startCaptureWithCompletionHandler:^(NSError * error) {
                // A permission prompt can outlive the caller's deadline. A late
                // successful start must not leave a recording running invisibly.
                if (completed->abandoned.load() && !error) {
                    // Keep the detached receiver alive until the late stream
                    // has stopped, even when the original stop timed out too.
                    [starting stopCaptureWithCompletionHandler:^(NSError * stopError) {
                        completed->Finish(stopError);
                    }];
                }
                else completed->Finish(error);
            }];
            completed->Wait();
        }
    }
    void Poll() override { }
};
}
std::unique_ptr<SystemAudioCapture> SystemAudioCapture::Create() {
    if (@available(macOS 13.0, *)) return std::make_unique<ScreenAudioCapture>();
    throw Exception("System audio requires macOS 13 or later");
}
}
