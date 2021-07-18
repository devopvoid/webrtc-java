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

#ifndef JNI_WEBRTC_MEDIA_MF_VIDEO_DEVICE_MANAGER_H_
#define JNI_WEBRTC_MEDIA_MF_VIDEO_DEVICE_MANAGER_H_

#include "media/video/VideoDeviceManager.h"
#include "platform/windows/WinHotplugNotifier.h"

namespace jni
{
	namespace avdev
	{
		class WindowsVideoDeviceManager : public VideoDeviceManager, WinHotplugNotifier
		{
			public:
				WindowsVideoDeviceManager();
				~WindowsVideoDeviceManager() = default;

				std::set<VideoDevicePtr> getVideoCaptureDevices() override;
				std::set<VideoCaptureCapability> getVideoCaptureCapabilities(const VideoDevice & device) override;

			protected:
				void onDeviceConnected(std::wstring symLink);
				void onDeviceDisconnected(std::wstring symLink);

			private:
				void enumerateDevices(std::wstring * symLink);
				VideoDevicePtr createVideoDevice(WCHAR * symbolicLink, WCHAR * friendlyName);

				bool insertVideoDevice(VideoDevicePtr device);
		};
	}
}

#endif