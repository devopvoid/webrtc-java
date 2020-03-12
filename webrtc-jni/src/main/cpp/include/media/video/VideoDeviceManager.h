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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_DEVICE_MANAGER_H_
#define JNI_WEBRTC_MEDIA_VIDEO_DEVICE_MANAGER_H_

#include "media/DeviceManager.h"
#include "media/DeviceList.h"
#include "media/video/VideoDevice.h"
#include "media/video/VideoCaptureCapability.h"

#include "modules/video_capture/video_capture_defines.h"

#include <mutex>
#include <set>

namespace jni
{
	namespace avdev
	{
		using VideoDevicePtr = std::shared_ptr<VideoDevice>;


		class VideoDeviceManager : public DeviceManager
		{
			public:
				VideoDeviceManager();
				virtual ~VideoDeviceManager() {};

				VideoDevicePtr getDefaultVideoCaptureDevice();

				virtual std::set<VideoDevicePtr> getVideoCaptureDevices() = 0;
				virtual std::set<VideoCaptureCapability> getVideoCaptureCapabilities(const VideoDevice & device) = 0;

			protected:
				void setDefaultCaptureDevice(VideoDevicePtr device);
				VideoDevicePtr getDefaultCaptureDevice();

			protected:
				DeviceList<VideoDevicePtr> captureDevices;

			private:
				VideoDevicePtr defaultCaptureDevice;
				std::mutex mutex;
		};
	}
}

#endif