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

#ifndef JNI_WEBRTC_MEDIA_V4L2_VIDEO_DEVICE_MANAGER_H_
#define JNI_WEBRTC_MEDIA_V4L2_VIDEO_DEVICE_MANAGER_H_

#include "media/video/VideoDeviceManager.h"

#include <atomic>
#include <thread>
#include <libudev.h>

namespace jni
{
	namespace avdev
	{
		class V4l2VideoDeviceManager : public VideoDeviceManager
		{
			public:
				V4l2VideoDeviceManager();
				~V4l2VideoDeviceManager();

				std::set<VideoDevicePtr> getVideoCaptureDevices() override;
				std::set<VideoCaptureCapability> getVideoCaptureCapabilities(const VideoDevice & device) override;

			private:
				void addDevice(const std::string & name, const std::string & descriptor);
				void removeDevice(const std::string & name, const std::string & descriptor);
				void run();
				bool checkDevice(const std::string & descriptor);
				int ioctlDevice(int fh, int request, void * arg);
				webrtc::VideoType toVideoType(const std::uint32_t & v4l2MediaType);

				struct MediaFormatConfiguration {
					std::uint32_t v4l2MediaType;
					webrtc::VideoType videoType;
				};

			private:
				std::thread thread;
				std::atomic<bool> running;
				struct udev * udev;
		};
	}
}

#endif