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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_TRACK_DEVICE_SOURCE_MAC_H_
#define JNI_WEBRTC_MEDIA_VIDEO_TRACK_DEVICE_SOURCE_MAC_H_

#include "media/base/adapted_video_track_source.h"
#include "modules/video_capture/video_capture_defines.h"
#include "rtc_base/timestamp_aligner.h"

#include "sdk/objc/base/RTCVideoCapturer.h"
#include "sdk/objc/base/RTCMacros.h"
#include "sdk/objc/components/capturer/RTCCameraVideoCapturer.h"
#include "sdk/objc/native/api/video_capturer.h"

#include "media/video/VideoTrackDeviceSourceBase.h"
#include "media/video/VideoDevice.h"

@interface VideoTrackDeviceSourceCallback
    : NSObject <RTC_OBJC_TYPE (RTCVideoCapturerDelegate)>
@end

namespace jni
{
	class VideoTrackDeviceSourceMac : public webrtc::AdaptedVideoTrackSource, public VideoTrackDeviceSourceBase
	{
		public:
			VideoTrackDeviceSourceMac();
			~VideoTrackDeviceSourceMac();

            // VideoTrackDeviceSourceBase implementation.
            void start() override;
            void stop() override;

            // AdaptedVideoTrackSource implementation.
            virtual bool is_screencast() const override;
            virtual std::optional<bool> needs_denoising() const override;
            SourceState state() const override;
            bool remote() const override;

            void OnCapturedFrame(RTC_OBJC_TYPE(RTCVideoFrame) * frame);

		private:
			void destroy();

		private:
            webrtc::TimestampAligner timestamp_aligner;

            RTC_OBJC_TYPE(RTCCameraVideoCapturer) * cameraVideoCapturer;
	};
}

#endif