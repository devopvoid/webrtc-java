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

#include "media/video/macos/VideoTrackDeviceSourceMac.h"
#include "Exception.h"

#include "api/video/i420_buffer.h"

#include "base/RTCLogging.h"
#include "sdk/objc/components/video_frame_buffer/RTCCVPixelBuffer.h"
#include "sdk/objc/native/src/objc_frame_buffer.h"

@interface VideoTrackDeviceSourceCallback ()
@property(nonatomic) jni::VideoTrackDeviceSourceMac * videoTrackSource;
@end

@implementation VideoTrackDeviceSourceCallback

@synthesize videoTrackSource = _videoTrackSource;

- (void)capturer:(RTC_OBJC_TYPE(RTCVideoCapturer) *)capturer
    didCaptureVideoFrame:(RTC_OBJC_TYPE(RTCVideoFrame) *)frame {
  _videoTrackSource->OnCapturedFrame(frame);
}

@end


namespace jni
{
	VideoTrackDeviceSourceMac::VideoTrackDeviceSourceMac() :
        AdaptedVideoTrackSource(),
        VideoTrackDeviceSourceBase(),
		cameraVideoCapturer(nullptr)
	{
		printf("VideoTrackDeviceSourceMac::VideoTrackDeviceSourceMac\n");
		fflush(NULL);
	}

	VideoTrackDeviceSourceMac::~VideoTrackDeviceSourceMac()
	{
		printf("VideoTrackDeviceSourceMac::~VideoTrackDeviceSourceMac()\n");
    	fflush(NULL);

		destroy();
	}

	void VideoTrackDeviceSourceMac::start()
	{
	    printf("VideoTrackDeviceSourceMac::start\n");
	    fflush(NULL);

        AVCaptureDevice * captureDevice = nullptr;

        AVCaptureDeviceDiscoverySession * captureDeviceDiscoverySession = [AVCaptureDeviceDiscoverySession discoverySessionWithDeviceTypes:@[
                AVCaptureDeviceTypeBuiltInWideAngleCamera,
                AVCaptureDeviceTypeExternalUnknown
            ]
            mediaType:AVMediaTypeVideo
            position:AVCaptureDevicePositionUnspecified];

        NSArray<AVCaptureDevice *> * devices = captureDeviceDiscoverySession.devices;

		if (!device && devices.count > 0) {
			// No user-defined capture device. Select first available device.
            captureDevice = devices[0];
		}
		else {
            // Use user-defined capture device.
            for (AVCaptureDevice * avDevice in devices) {
                if ([avDevice.localizedName isEqualToString:[NSString stringWithUTF8String:device->getName().c_str()]]) {
                    captureDevice = avDevice;
                    break;
                }
            }
        }

        if (!captureDevice) {
            throw new Exception("No video capture devices available");
        }

        std::string deviceName = captureDevice.localizedName.UTF8String;
        printf("VideoTrackDeviceSourceMac::start: %s\n", deviceName.c_str());
        fflush(NULL);

        VideoTrackDeviceSourceCallback * callback = [[VideoTrackDeviceSourceCallback alloc] init];
        cameraVideoCapturer = [[RTCCameraVideoCapturer alloc] init];
        cameraVideoCapturer.delegate = callback;

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

	void VideoTrackDeviceSourceMac::stop()
	{
        if (!cameraVideoCapturer) {
            return;
        }

        [cameraVideoCapturer stopCapture];
	}

	void VideoTrackDeviceSourceMac::destroy()
	{
        stop();

        cameraVideoCapturer = nullptr;
	}

	bool VideoTrackDeviceSourceMac::is_screencast() const {
		return false;
	}

	std::optional<bool> VideoTrackDeviceSourceMac::needs_denoising() const {
		return false;
	}

	webrtc::MediaSourceInterface::SourceState VideoTrackDeviceSourceMac::state() const {
		return SourceState::kLive;
	}

	bool VideoTrackDeviceSourceMac::remote() const {
		return false;
	}

    void VideoTrackDeviceSourceMac::OnCapturedFrame(RTC_OBJC_TYPE(RTCVideoFrame) * frame) {
        printf("VideoTrackDeviceSourceMac::OnCapturedFrame: %lld\n", frame.timeStampNs);
        fflush(NULL);

        const int64_t timestamp_us = frame.timeStampNs / webrtc::kNumNanosecsPerMicrosec;
        //const int64_t translated_timestamp_us = timestamp_aligner.TranslateTimestamp(timestamp_us, webrtc::TimeMicros());

        int adapted_width;
        int adapted_height;
        int crop_width;
        int crop_height;
        int crop_x;
        int crop_y;

        if (!AdaptFrame(frame.width, frame.height,
                timestamp_us,
                &adapted_width, &adapted_height,
                &crop_width, &crop_height, &crop_x, &crop_y)) {
            return;
        }
/*
        webrtc::scoped_refptr<webrtc::VideoFrameBuffer> buffer;

        if (adapted_width == frame.width && adapted_height == frame.height) {
            // No adaption - optimized path.
            buffer = webrtc::make_ref_counted<webrtc::ObjCFrameBuffer>(frame.buffer);
        }
        else if ([frame.buffer isKindOfClass:[RTC_OBJC_TYPE(RTCCVPixelBuffer) class]]) {
            // Adapted CVPixelBuffer frame.
            RTC_OBJC_TYPE(RTCCVPixelBuffer) *rtcPixelBuffer = (RTC_OBJC_TYPE(RTCCVPixelBuffer) *)frame.buffer;
            buffer = webrtc::make_ref_counted<webrtc::ObjCFrameBuffer>([[RTC_OBJC_TYPE(RTCCVPixelBuffer) alloc]
                    initWithPixelBuffer:rtcPixelBuffer.pixelBuffer
                    adaptedWidth:adapted_width
                    adaptedHeight:adapted_height
                    cropWidth:crop_width
                    cropHeight:crop_height
                    cropX:crop_x + rtcPixelBuffer.cropX
                    cropY:crop_y + rtcPixelBuffer.cropY]);
        }
        else {
            // Adapted I420 frame.
            // TODO(magjed): Optimize this I420 path.
            webrtc::scoped_refptr<webrtc::I420Buffer> i420_buffer = webrtc::I420Buffer::Create(adapted_width, adapted_height);
            buffer = webrtc::make_ref_counted<webrtc::ObjCFrameBuffer>(frame.buffer);
            i420_buffer->CropAndScaleFrom(*buffer->ToI420(), crop_x, crop_y, crop_width, crop_height);
            buffer = i420_buffer;
        }

        OnFrame(webrtc::VideoFrame::Builder()
                .set_video_frame_buffer(buffer)
                .set_rotation(webrtc::kVideoRotation_0)
                .set_timestamp_us(translated_timestamp_us)
                .build());
*/
    }
}