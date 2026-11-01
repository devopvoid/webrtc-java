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

#include "rtc_base/logging.h"
#include "modules/video_capture/video_capture_factory.h"

namespace jni
{
	VideoCapture::VideoCapture() : VideoCaptureBase(),
		captureModule(nullptr)
	{
	}

	VideoCapture::~VideoCapture()
	{
		destroy();
	}

	void VideoCapture::setVideoSink(std::unique_ptr<webrtc::VideoSinkInterface<webrtc::VideoFrame>> newSink)
	{
		if (captureModule) {
			// Deregistering takes the lock the module delivers frames under, so
			// no callback into the old sink is running once this returns.
			captureModule->DeRegisterCaptureDataCallback();
		}

		sink = std::move(newSink);

		if (captureModule && sink) {
			captureModule->RegisterCaptureDataCallback(sink.get());
		}
	}

	void VideoCapture::start()
	{
		if (!device) {
			throw Exception("Video device must be set");
		}

		if (captureModule && captureModule->CaptureStarted()) {
			return;
		}

		// Release a module whose capture is no longer running.
		destroy();

		std::unique_ptr<webrtc::VideoCaptureModule::DeviceInfo> info(webrtc::VideoCaptureFactory::CreateDeviceInfo());

		if (!info) {
			throw Exception("Create video DeviceInfo failed");
		}

		uint32_t num = info->NumberOfDevices();

		if (num < 1) {
			throw Exception("No video capture devices available");
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
			throw Exception("Device %s not found", device->getName().c_str());
		}

		captureModule = webrtc::VideoCaptureFactory::Create(devUid.c_str());

		if (!captureModule) {
			throw Exception("Create VideoCaptureModule for UID %s failed", devUid.c_str());
		}

		// The module only borrows the sink, which stays owned by this capture.
		if (sink) {
			captureModule->RegisterCaptureDataCallback(sink.get());
		}

		capability.videoType = webrtc::VideoType::kI420;

		if (captureModule->StartCapture(capability) != 0 || !captureModule->CaptureStarted()) {
			destroy();

			throw Exception("Start video capture for UID %s failed", devUid.c_str());
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