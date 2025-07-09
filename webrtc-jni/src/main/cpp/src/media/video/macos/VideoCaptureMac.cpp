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

#include "media/video/macos/VideoCaptureMac.h"
#include "media/video/macos/VideoCaptureDelegateMac.h"
#include "Exception.h"

#include "api/video/i420_buffer.h"
#include "modules/video_capture/video_capture_factory.h"
#include "rtc_base/logging.h"

namespace jni
{
	VideoCaptureMac::VideoCaptureMac() :
		cameraVideoCapturer(nullptr)
	{
		capability.width = static_cast<int32_t>(1280);
		capability.height = static_cast<int32_t>(720);
		capability.maxFPS = static_cast<int32_t>(30);
	}

	VideoCaptureMac::~VideoCaptureMac()
	{
		destroy();
	}

	void VideoCaptureMac::setDevice(const avdev::DevicePtr & device)
	{
		this->device = device;
	}

	void VideoCaptureMac::setVideoCaptureCapability(const webrtc::VideoCaptureCapability & capability)
	{
		this->capability = capability;
	}

	void VideoCaptureMac::setVideoSink(std::unique_ptr<webrtc::VideoSinkInterface<webrtc::VideoFrame>> sink)
	{
		this->sink = std::move(sink);
	}

	void VideoCaptureMac::start()
	{
		if (!device) {
			throw new Exception("Video device must be set");
		}

		AVCaptureDevice * mCaptureDevice = nullptr;

		AVCaptureDeviceDiscoverySession * captureDeviceDiscoverySession = [AVCaptureDeviceDiscoverySession discoverySessionWithDeviceTypes:@[
                    AVCaptureDeviceTypeBuiltInWideAngleCamera,
                    AVCaptureDeviceTypeExternalUnknown
                ]
                mediaType:AVMediaTypeVideo
                position:AVCaptureDevicePositionUnspecified];

        for (AVCaptureDevice * captureDevice in captureDeviceDiscoverySession.devices) {
            if ([captureDevice.localizedName isEqualToString:[NSString stringWithUTF8String:device->getName().c_str()]]) {
                mCaptureDevice = captureDevice;
            }
        }

        if (!mCaptureDevice) {
            throw new Exception("No video capture devices available");
        }

        AVCaptureDeviceFormat * selectedFormat = nullptr;
        int currentDiff = INT_MAX;
        int targetWidth = capability.width;
        int targetHeight = capability.height;

        for (AVCaptureDeviceFormat * format in mCaptureDevice.formats) {
            CMVideoDimensions dimension = CMVideoFormatDescriptionGetDimensions(format.formatDescription);
            FourCharCode pixelFormat = CMFormatDescriptionGetMediaSubType(format.formatDescription);

            int diff = abs(targetWidth - dimension.width) + abs(targetHeight - dimension.height);
            if (diff < currentDiff) {
                selectedFormat = format;
                currentDiff = diff;
            }
            else if (diff == currentDiff && pixelFormat == [cameraVideoCapturer preferredOutputPixelFormat]) {
                selectedFormat = format;
            }
        }

        if (!selectedFormat) {
            selectedFormat = mCaptureDevice.activeFormat;
        }

        VideoCaptureDelegateMac * delegate = [[VideoCaptureDelegateMac alloc] initWithCppHandler:(VideoCaptureMac*)this];
        cameraVideoCapturer = [[RTCCameraVideoCapturer alloc] initWithDelegate:delegate];

		if (!cameraVideoCapturer) {
		    std::string deviceName = mCaptureDevice.localizedName.UTF8String;
			throw new Exception("Create VideoCaptureModule for UID %s failed", deviceName.c_str());
		}

		void (^CaptureCompletionHandler)(NSError * _Nullable error) = ^(NSError * _Nullable error) {
            if (error) {
                NSLog(@"Failed to start capture: %@", error.localizedDescription);
            }
            else {
                NSLog(@"Capture started successfully");
            }
        };

		[cameraVideoCapturer startCaptureWithDevice:mCaptureDevice
		        format:selectedFormat
		        fps:capability.maxFPS
		        completionHandler:CaptureCompletionHandler];
	}

	void VideoCaptureMac::stop()
	{
		destroy();
	}

	void VideoCaptureMac::destroy()
	{
		if (!cameraVideoCapturer) {
			return;
		}

		[cameraVideoCapturer stopCapture];
		cameraVideoCapturer = nullptr;
	}

    void VideoCaptureMac::OnFrame(const webrtc::VideoFrame & frame)
    {
        if (sink) {
            sink->OnFrame(frame);
        }
    }
}