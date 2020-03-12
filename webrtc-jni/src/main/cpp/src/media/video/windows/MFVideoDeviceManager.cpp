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

#include "media/video/windows/MFVideoDeviceManager.h"
#include "platform/windows/ComPtr.h"
#include "platform/windows/MFInitializer.h"
#include "platform/windows/MFUtils.h"
#include "platform/windows/WinUtils.h"

#include <algorithm>
#include <codecvt>
#include <ks.h>
#include <ksmedia.h>
#include <locale>
#include <mfapi.h>
#include <mferror.h>
#include <mfidl.h>
#include <mfobjects.h>
#include <mfreadwrite.h>

#include "rtc_base/logging.h"

namespace jni
{
	namespace avdev
	{
		MFVideoDeviceManager::MFVideoDeviceManager() :
			WinHotplugNotifier(std::list<GUID> { KSCATEGORY_VIDEO })
		{
			start();
		}

		std::set<VideoDevicePtr> MFVideoDeviceManager::getVideoCaptureDevices()
		{
			if (captureDevices.empty()) {
				enumerateDevices(nullptr);
			}

			return captureDevices.devices();
		}

		std::set<VideoCaptureCapability> MFVideoDeviceManager::getVideoCaptureCapabilities(const VideoDevice & device)
		{
			std::set<VideoCaptureCapability> capabilities;
			MFInitializer initializer;
			ComPtr<IMFMediaSource> mediaSource;
			ComPtr<IMFSourceReader> sourceReader;
			ComPtr<IMFMediaType> type;
			DWORD typeIndex = 0;
			HRESULT hr;

			mf::CreateMediaSource(MFMediaType_Video, device.getDescriptor(), &mediaSource);

			hr = MFCreateSourceReaderFromMediaSource(mediaSource, nullptr, &sourceReader);
			THROW_IF_FAILED(hr, "MMF: Create source reader from media source failed");

			while (SUCCEEDED(hr)) {
				hr = sourceReader->GetNativeMediaType(0, typeIndex, &type);

				if (hr == MF_E_NO_MORE_TYPES) {
					break;
				}
				else if (SUCCEEDED(hr)) {
					UINT32 width, height;
					UINT32 numerator, denominator;
					GUID guid;

					hr = MFGetAttributeSize(type, MF_MT_FRAME_SIZE, &width, &height);
					THROW_IF_FAILED(hr, "MMF: Get frame size failed");

					hr = MFGetAttributeSize(type, MF_MT_FRAME_RATE_RANGE_MAX, &numerator, &denominator);
					THROW_IF_FAILED(hr, "MMF: Get frame rate failed");

					hr = type->GetGUID(MF_MT_SUBTYPE, &guid);
					THROW_IF_FAILED(hr, "MMF: Get type guid failed");

					VideoCaptureCapability capability;
					capability.width = width;
					capability.height = height;
					capability.maxFPS = static_cast<int32_t>(numerator / denominator);
					capability.videoType = mf::toVideoType(guid);

					capabilities.emplace(capability);
				}

				++typeIndex;
			}

			return capabilities;
		}

		void MFVideoDeviceManager::enumerateDevices(std::wstring * symLink)
		{
			MFInitializer initializer;
			ComPtr<IMFAttributes> pAttributes;
			UINT32 deviceCount = 0;
			IMFActivate ** ppDevices;
			HRESULT hr;

			hr = MFCreateAttributes(&pAttributes, 1);
			THROW_IF_FAILED(hr, "MMF: Create attributes failed");

			hr = pAttributes->SetGUID(MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE, MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID);
			THROW_IF_FAILED(hr, "MMF: Set video source GUID failed");

			hr = MFEnumDeviceSources(pAttributes, &ppDevices, &deviceCount);
			THROW_IF_FAILED(hr, "MMF: Enumerate video sources failed");

			for (DWORD i = 0; i < deviceCount; i++) {
				WCHAR * friendlyName = nullptr;
				WCHAR * symbolicLink = nullptr;

				hr = ppDevices[i]->GetAllocatedString(MF_DEVSOURCE_ATTRIBUTE_FRIENDLY_NAME, &friendlyName, nullptr);
				if (FAILED(hr)) {
					RTC_LOG(LS_ERROR) << "MMF: Get video source name failed";
					continue;
				}
				hr = ppDevices[i]->GetAllocatedString(MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_SYMBOLIC_LINK, &symbolicLink, nullptr);
				if (FAILED(hr)) {
					RTC_LOG(LS_ERROR) << "MMF: Get video source symbolic link failed";
					continue;
				}

				if (symLink == nullptr) {
					std::shared_ptr<VideoDevice> device = createVideoDevice(symbolicLink, friendlyName);
					insertVideoDevice(device);
				}
				else {
					// IMFActivate and the device broadcaster return different
					// symbolic links. Compare only the device instance id.
					std::wstring link(symbolicLink);
					size_t pos = link.find(L"#{", 0);
					pos = (pos == -1) ? (std::min)(link.length(), (*symLink).length()) : pos;

					if ((*symLink).compare(0, pos, link, 0, pos) == 0) {
						auto device = createVideoDevice(symbolicLink, friendlyName);

						if (insertVideoDevice(device)) {
							notifyDeviceConnected(device);
						}
						break;
					}
				}

				CoTaskMemFree(friendlyName);
				CoTaskMemFree(symbolicLink);

				ppDevices[i]->Release();
			}

			if (deviceCount != 0) {
				CoTaskMemFree(ppDevices);
				ppDevices = nullptr;
			}
		}

		VideoDevicePtr MFVideoDeviceManager::createVideoDevice(WCHAR * symbolicLink, WCHAR * friendlyName)
		{
			std::string id = WideStrToStr(symbolicLink);
			std::string name = WideStrToStr(friendlyName);

			return std::make_shared<VideoDevice>(name, id);
		}

		bool MFVideoDeviceManager::insertVideoDevice(VideoDevicePtr device)
		{
			if (device == nullptr) {
				return false;
			}

			captureDevices.insertDevice(device);

			return true;
		}

		void MFVideoDeviceManager::onDeviceConnected(std::wstring symLink)
		{
			enumerateDevices(&symLink);
		}

		void MFVideoDeviceManager::onDeviceDisconnected(std::wstring symLink)
		{
			std::wstring_convert<std::codecvt_utf8_utf16<wchar_t>> converter;

			auto predicate = [&converter, symLink](const VideoDevicePtr & dev) {
				std::wstring link = converter.from_bytes(dev->getDescriptor());
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