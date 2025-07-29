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
#include "media/base/adapted_video_track_source.h"
#include "rtc_base/ref_counted_object.h"

#include "media/SyncClock.h"

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

            void PushFrame(const webrtc::VideoFrame & frame);

        private:
            std::shared_ptr<SyncClock> clock_;
            uint16_t frame_id_;
    };
}
#endif