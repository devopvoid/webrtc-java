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

#include "media/video/VideoTrackDeviceSource.h"
#include "Exception.h"

#include "api/video/i420_buffer.h"
#include "modules/video_capture/video_capture_factory.h"

namespace jni
{
	VideoTrackDeviceSource::VideoTrackDeviceSource() :
		VideoTrackSource(/*remote=*/false),
		captureModule(nullptr)
	{
		capability.width = static_cast<int32_t>(1280);
		capability.height = static_cast<int32_t>(720);
		capability.maxFPS = static_cast<int32_t>(30);
	}

	VideoTrackDeviceSource::~VideoTrackDeviceSource()
	{
		destroy();
	}

	void VideoTrackDeviceSource::setVideoDevice(const avdev::VideoDevicePtr & device)
	{
		this->device = device;
	}

	void VideoTrackDeviceSource::setVideoCaptureCapability(const webrtc::VideoCaptureCapability & capability)
	{
		this->capability = capability;
	}

	void VideoTrackDeviceSource::start()
	{
		std::unique_ptr<webrtc::VideoCaptureModule::DeviceInfo> info(webrtc::VideoCaptureFactory::CreateDeviceInfo());

		if (!info) {
			throw new Exception("Create video DeviceInfo failed");
		}

		uint32_t num = info->NumberOfDevices();

		if (num < 1) {
			throw new Exception("No video capture devices available");
		}

		if (device) {
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

			if (!startCapture()) {
				destroy();

				throw new Exception("Start video capture for UID %s failed", devUid.c_str());
			}
		}

		if (!captureModule) {
			// No user-defined capture device. Select first available device.
			const uint32_t size = webrtc::kVideoCaptureDeviceNameLength;

			for (uint32_t i = 0; i < num; ++i) {
				char name[size] = { 0 };
				char guid[size] = { 0 };

				int32_t ret = info->GetDeviceName(i, name, size, guid, size);

				if (ret != 0) {
					RTC_LOG(LS_WARNING) << "Get video capture device name failed";
					continue;
				}

				captureModule = webrtc::VideoCaptureFactory::Create(guid);

				if (!captureModule) {
					continue;
				}

				if (startCapture()) {
					break;
				}
				else {
					// Clean up resources. Try next device.
					destroy();
				}
			}
		}

		if (!captureModule || !captureModule->CaptureStarted()) {
			throw new Exception("Start video capture failed");
		}
	}

	void VideoTrackDeviceSource::stop()
	{
		destroy();
	}

	void VideoTrackDeviceSource::destroy()
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

	void VideoTrackDeviceSource::AddOrUpdateSink(rtc::VideoSinkInterface<webrtc::VideoFrame> * sink, const rtc::VideoSinkWants & wants)
	{
		broadcaster.AddOrUpdateSink(sink, wants);

		updateVideoAdapter();
	}

	void VideoTrackDeviceSource::RemoveSink(rtc::VideoSinkInterface<webrtc::VideoFrame> * sink)
	{
		broadcaster.RemoveSink(sink);

		updateVideoAdapter();
	}

	bool VideoTrackDeviceSource::startCapture()
	{
		captureModule->RegisterCaptureDataCallback(this);

		capability.videoType = webrtc::VideoType::kI420;

		return captureModule->StartCapture(capability) == 0;
	}

	void VideoTrackDeviceSource::updateVideoAdapter()
	{
		rtc::VideoSinkWants wants = broadcaster.wants();

		videoAdapter.OnOutputFormatRequest(std::make_pair(capability.width, capability.height), wants.max_pixel_count, wants.max_framerate_fps);
	}

	void VideoTrackDeviceSource::OnFrame(const webrtc::VideoFrame & frame)
	{
		int croppedWidth = 0;
		int croppedHeight = 0;
		int outWidth = 0;
		int outHeight = 0;

		if (!videoAdapter.AdaptFrameResolution(frame.width(), frame.height(), frame.timestamp_us() * 1000,
			&croppedWidth, &croppedHeight, &outWidth, &outHeight)) {
			// Drop frame in order to respect frame rate constraint.
			return;
		}

		if (outHeight != frame.height() || outWidth != frame.width()) {
			// Video adapter has requested a down-scale. Allocate a new buffer and return scaled version.
			rtc::scoped_refptr<webrtc::I420Buffer> scaled_buffer = webrtc::I420Buffer::Create(outWidth, outHeight);

			scaled_buffer->ScaleFrom(*frame.video_frame_buffer()->ToI420());
			
			broadcaster.OnFrame(webrtc::VideoFrame::Builder()
				.set_video_frame_buffer(scaled_buffer)
				.set_rotation(webrtc::kVideoRotation_0)
				.set_timestamp_us(frame.timestamp_us())
				.set_id(frame.id())
				.build());
		}
		else {
			// No adaptations needed, just return the frame as is.
			broadcaster.OnFrame(frame);
		}
	}

	rtc::VideoSourceInterface<webrtc::VideoFrame> * VideoTrackDeviceSource::source()
	{
		return this;
	}
}