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

#include "media/video/VideoCapture.h"
#include "Exception.h"

#include "api/video/i420_buffer.h"
#include "modules/video_capture/video_capture_factory.h"

namespace jni
{
	VideoCapture::VideoCapture() :
		captureModule(nullptr)
	{
		capability.width = static_cast<int32_t>(1280);
		capability.height = static_cast<int32_t>(720);
		capability.maxFPS = static_cast<int32_t>(30);
	}

	VideoCapture::~VideoCapture()
	{
		destroy();
	}

	void VideoCapture::setDevice(const avdev::DevicePtr & device)
	{
		this->device = device;
	}

	void VideoCapture::setVideoCaptureCapability(const webrtc::VideoCaptureCapability & capability)
	{
		this->capability = capability;
	}

	void VideoCapture::setVideoSink(std::unique_ptr<rtc::VideoSinkInterface<webrtc::VideoFrame>> sink)
	{
		this->sink = std::move(sink);
	}

	void VideoCapture::start()
	{
		if (!device) {
			throw new Exception("Video device must be set");
		}

		std::unique_ptr<webrtc::VideoCaptureModule::DeviceInfo> info(webrtc::VideoCaptureFactory::CreateDeviceInfo());

		if (!info) {
			throw new Exception("Create video DeviceInfo failed");
		}

		uint32_t num = info->NumberOfDevices();

		if (num < 1) {
			throw new Exception("No video capture devices available");
		}
		
		std::string devUid;
		const uint32_t size = webrtc::kVideoCaptureDeviceNameLength;

		for (uint32_t i = 0; i < num; ++i) {
			char name[size] = { 0 };
			char guid[size] = { 0 };

			int32_t ret = info->GetDeviceName(i, name, size, guid, size);

			if (ret != 0) {
				RTC_LOG(LS_WARNING) << "Get video capture device name failed";
				continue;
			}

			if (device->getName().compare(name) == 0) {
				devUid = guid;
				break;
			}
		}

		if (devUid.empty()) {
			throw new Exception("Device %s not found", device->getName().c_str());
		}

		captureModule = webrtc::VideoCaptureFactory::Create(devUid.c_str());

		if (!captureModule) {
			throw new Exception("Create VideoCaptureModule for UID %s failed", devUid.c_str());
		}

		captureModule->RegisterCaptureDataCallback(std::move(sink).release());

		capability.videoType = webrtc::VideoType::kI420;

		if (captureModule->StartCapture(capability) != 0) {
			destroy();

			throw new Exception("Start video capture for UID %s failed", devUid.c_str());
		}

		if (!captureModule || !captureModule->CaptureStarted()) {
			throw new Exception("Start video capture failed");
		}
	}

	void VideoCapture::stop()
	{
		destroy();
	}

	void VideoCapture::destroy()
	{
		if (!captureModule) {
			return;
		}
		if (captureModule->CaptureStarted()) {
			captureModule->StopCapture();
		}

		captureModule->DeRegisterCaptureDataCallback();
		captureModule = nullptr;
	}
}