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

#include "Exception.h"
#include "media/video/windows/WindowsVideoDeviceManager.h"
#include "platform/windows/WinUtils.h"

#include "modules/video_capture/video_capture_factory.h"

#include <algorithm>
#include <ks.h>
#include <ksmedia.h>

namespace jni
{
	namespace avdev
	{
		WindowsVideoDeviceManager::WindowsVideoDeviceManager() :
			WinHotplugNotifier(std::list<GUID> { KSCATEGORY_VIDEO })
		{
			start();
		}

		std::set<VideoDevicePtr> WindowsVideoDeviceManager::getVideoCaptureDevices()
		{
			if (captureDevices.empty()) {
				enumerateDevices(nullptr);
			}

			return captureDevices.devices();
		}

		std::set<VideoCaptureCapability> WindowsVideoDeviceManager::getVideoCaptureCapabilities(const VideoDevice & device)
		{
			std::set<VideoCaptureCapability> capabilities;

			std::unique_ptr<webrtc::VideoCaptureModule::DeviceInfo> info(webrtc::VideoCaptureFactory::CreateDeviceInfo());

			if (!info) {
				throw jni::Exception("Create video DeviceInfo failed");
			}

			const std::string guid = device.getDescriptor();

			uint32_t capabilitiesCount = info->NumberOfCapabilities(guid.data());

			if (capabilitiesCount > 0) {
				for (uint32_t i = 0; i < capabilitiesCount; ++i) {
					webrtc::VideoCaptureCapability capability;

					if (info->GetCapability(guid.data(), i, capability) == 0) {
						VideoCaptureCapability cap;
						cap.width = capability.width;
						cap.height = capability.height;
						cap.maxFPS = capability.maxFPS;
						cap.videoType = capability.videoType;

						capabilities.emplace(cap);
					}
				}
			}

			return capabilities;
		}

		void WindowsVideoDeviceManager::enumerateDevices(std::wstring * symLink)
		{
			std::unique_ptr<webrtc::VideoCaptureModule::DeviceInfo> info(webrtc::VideoCaptureFactory::CreateDeviceInfo());

			if (!info) {
				throw jni::Exception("Create video DeviceInfo failed");
			}

			uint32_t deviceCount = info->NumberOfDevices();

			if (deviceCount > 0) {
				const uint32_t size = webrtc::kVideoCaptureDeviceNameLength;

				for (uint32_t i = 0; i < deviceCount; ++i) {
					char name[size] = { 0 };
					char guid[size] = { 0 };

					if (info->GetDeviceName(i, name, size, guid, size) == 0) {
						std::shared_ptr<VideoDevice> device = std::make_shared<VideoDevice>(name, guid);

						insertVideoDevice(device);
					}
				}
			}
		}

		bool WindowsVideoDeviceManager::insertVideoDevice(VideoDevicePtr device)
		{
			if (device == nullptr) {
				return false;
			}

			captureDevices.insertDevice(device);

			return true;
		}

		void WindowsVideoDeviceManager::onDeviceConnected(std::wstring symLink)
		{
			enumerateDevices(&symLink);
		}

		void WindowsVideoDeviceManager::onDeviceDisconnected(std::wstring symLink)
		{
			auto predicate = [symLink](const VideoDevicePtr & dev) {
				std::wstring link = UTF8Decode(dev->getDescriptor());
				std::transform(link.begin(), link.end(), link.begin(), ::tolower);

				// IMFActivate and the device broadcaster return different
				// symbolic links. Compare only the device instance id.
				size_t pos = link.find(L"#{", 0);
				pos = (pos == -1) ? (std::min)(link.length(), symLink.length()) : pos;

				return symLink.compare(0, pos, link, 0, pos) == 0;
			};

			VideoDevicePtr removed = captureDevices.removeDevice(predicate);

			if (removed) {
				notifyDeviceDisconnected(removed);
			}
		}
	}
}