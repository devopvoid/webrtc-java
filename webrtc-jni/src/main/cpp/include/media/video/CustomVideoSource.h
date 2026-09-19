/*
 * Copyright 2019 Alex Andres
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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CUSTOM_VIDEO_SOURCE_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CUSTOM_VIDEO_SOURCE_H_

#include "api/video/video_frame.h"
#include "api/video/video_source_interface.h"
#include "api/video/adapted_video_track_source.h"
#include "rtc_base/ref_counted_object.h"

#include "media/SyncClock.h"

#include <atomic>
#include <memory>

namespace jni
{
    class CustomVideoSource : public webrtc::AdaptedVideoTrackSource
    {
        public:
            explicit CustomVideoSource(std::shared_ptr<SyncClock> clock);

            // AdaptedVideoTrackSource implementation.
            virtual bool is_screencast() const override;
            virtual std::optional<bool> needs_denoising() const override;
            SourceState state() const override;
            bool remote() const override;

            // Delivers a frame captured now, stamping it with this source's
            // clock. This is what the Java pushFrame() calls, so a caller that
            // has no capture time of its own gets the timing of the moment it
            // pushes.
            void PushFrame(const webrtc::VideoFrame & frame);

            // Delivers a frame captured at the given time, in microseconds on
            // the same clock as webrtc::TimeMicros().
            //
            // A caller that knows when a frame was meant to be shown, such as
            // one playing a media file, should use this: the encoder derives
            // the outgoing RTP timestamp from the frame's NTP capture time
            // (VideoStreamEncoder::OnFrame), so passing the presentation time
            // keeps the sent timeline free of the jitter of the pushing
            // thread, and keeps video lined up with audio pushed alongside it.
            void PushFrame(const webrtc::VideoFrame & frame, int64_t timestamp_us);

        private:
            void DeliverFrame(const webrtc::VideoFrame & frame, int64_t timestamp_us,
                              int64_t ntp_time_ms);

            std::shared_ptr<SyncClock> clock_;
            std::atomic<uint16_t> frame_id_;
    };
}
#endif