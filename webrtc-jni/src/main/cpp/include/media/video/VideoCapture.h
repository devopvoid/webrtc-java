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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CAPTURE_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CAPTURE_H_

#include "media/video/VideoDevice.h"

#include "api/create_peerconnection_factory.h"
#include "api/video/video_sink_interface.h"
#include "media/base/video_adapter.h"
#include "modules/video_capture/video_capture.h"
#include "modules/video_capture/video_capture_defines.h"

namespace jni
{
	class VideoCapture
	{
		public:
			VideoCapture();
			~VideoCapture();

			void setDevice(const avdev::DevicePtr & device);
			void setVideoCaptureCapability(const webrtc::VideoCaptureCapability & capability);
			void setVideoSink(std::unique_ptr<rtc::VideoSinkInterface<webrtc::VideoFrame>> sink);
			void start();
			void stop();
			void destroy();

		private:
			avdev::DevicePtr device;
			webrtc::VideoCaptureCapability capability;
			std::unique_ptr<rtc::VideoSinkInterface<webrtc::VideoFrame>> sink;

			rtc::scoped_refptr<webrtc::VideoCaptureModule> captureModule;
	};
}

#endif