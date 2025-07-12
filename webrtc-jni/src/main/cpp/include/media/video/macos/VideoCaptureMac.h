/*
 * Copyright 2021 Alex Andres
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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CAPTURE_MAC_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CAPTURE_MAC_H_

#include "media/video/VideoCaptureBase.h"

#include "sdk/objc/components/capturer/RTCCameraVideoCapturer.h"
#include "sdk/objc/native/api/video_capturer.h"

namespace jni
{
	class VideoCaptureMac : public VideoCaptureBase
	{
		public:
			VideoCaptureMac();
			~VideoCaptureMac();

            void start() override;
			void stop() override;
			void destroy() override;

			void OnFrame(const webrtc::VideoFrame & frame);

		private:
            RTCCameraVideoCapturer * cameraVideoCapturer;
	};
}

#endif