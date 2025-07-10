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

#import "base/RTCLogging.h"

namespace jni
{
	VideoCaptureMac::VideoCaptureMac() : VideoCaptureBase(),
		cameraVideoCapturer(nullptr)
	{
	}

	void VideoCaptureMac::start()
	{
		if (!device) {
			throw new Exception("Video device must be set");
		}

		AVCaptureDevice * captureDevice = nullptr;

		AVCaptureDeviceDiscoverySession * captureDeviceDiscoverySession = [AVCaptureDeviceDiscoverySession discoverySessionWithDeviceTypes:@[
                    AVCaptureDeviceTypeBuiltInWideAngleCamera,
                    AVCaptureDeviceTypeExternalUnknown
                ]
                mediaType:AVMediaTypeVideo
                position:AVCaptureDevicePositionUnspecified];

        for (AVCaptureDevice * avDevice in captureDeviceDiscoverySession.devices) {
            if ([avDevice.localizedName isEqualToString:[NSString stringWithUTF8String:device->getName().c_str()]]) {
                captureDevice = avDevice;
            }
        }

        if (!captureDevice) {
            throw new Exception("No video capture devices available");
        }

        AVCaptureDeviceFormat * selectedFormat = nullptr;
        int currentDiff = INT_MAX;
        int targetWidth = capability.width;
        int targetHeight = capability.height;

        for (AVCaptureDeviceFormat * format in captureDevice.formats) {
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
            selectedFormat = captureDevice.activeFormat;
        }

        VideoCaptureDelegateMac * delegate = [[VideoCaptureDelegateMac alloc] initWithHandler:(VideoCaptureMac*)this];
        cameraVideoCapturer = [[RTCCameraVideoCapturer alloc] initWithDelegate:delegate];

		if (!cameraVideoCapturer) {
		    std::string deviceName = captureDevice.localizedName.UTF8String;
			throw new Exception("Create VideoCaptureModule for UID %s failed", deviceName.c_str());
		}

		void (^CaptureCompletionHandler)(NSError * _Nullable error) = ^(NSError * _Nullable error) {
            if (error) {
                RTCLogError(@"Failed to start capture: %@", error.localizedDescription);
            }
            else {
                RTCLogInfo(@"Capture started successfully");
            }
        };

		[cameraVideoCapturer startCaptureWithDevice:captureDevice
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