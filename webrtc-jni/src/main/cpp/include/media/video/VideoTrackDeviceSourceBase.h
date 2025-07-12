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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_TRACK_DEVICE_SOURCE_BASE_H_
#define JNI_WEBRTC_MEDIA_VIDEO_TRACK_DEVICE_SOURCE_BASE_H_

#include "modules/video_capture/video_capture_defines.h"

#include "media/video/VideoDevice.h"

namespace jni
{
	class VideoTrackDeviceSourceBase
	{
		public:
			VideoTrackDeviceSourceBase();
			~VideoTrackDeviceSourceBase();

			void setVideoDevice(const avdev::VideoDevicePtr & device);
			void setVideoCaptureCapability(const webrtc::VideoCaptureCapability & capability);

			virtual void start() = 0;
			virtual void stop() = 0;

		protected:
			avdev::VideoDevicePtr device;
			webrtc::VideoCaptureCapability capability;
	};
}

#endif