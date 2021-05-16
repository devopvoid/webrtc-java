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

#include "media/video/macos/AVFVideoDeviceManager.h"

#include "rtc_base/logging.h"

namespace jni
{
	namespace avdev
	{
		AVFVideoDeviceManager::AVFVideoDeviceManager() :
			VideoDeviceManager()
		{
			// Set up hot-plug listeners.
			NSNotificationCenter * notificationCenter = [NSNotificationCenter defaultCenter];

			void (^deviceConnectedBlock)(NSNotification *) = ^(NSNotification * notification) {
				AVCaptureDevice * device = [notification object];
				onDeviceConnected(device);
			};

			void (^deviceDisconnectedBlock)(NSNotification *) = ^(NSNotification * notification) {
				AVCaptureDevice * device = [notification object];
				onDeviceDisconnected(device);
			};

			devConnectObserver = [notificationCenter addObserverForName: AVCaptureDeviceWasConnectedNotification
														object: nil
														queue: [NSOperationQueue mainQueue]
														usingBlock: deviceConnectedBlock];

			devDisconnectObserver = [notificationCenter addObserverForName: AVCaptureDeviceWasDisconnectedNotification
														object: nil
														queue: [NSOperationQueue mainQueue]
														usingBlock: deviceDisconnectedBlock];
		}

		AVFVideoDeviceManager::~AVFVideoDeviceManager()
		{
			NSNotificationCenter * notificationCenter = [NSNotificationCenter defaultCenter];
			[notificationCenter removeObserver: devConnectObserver];
			[notificationCenter removeObserver: devDisconnectObserver];
		}

		std::set<VideoDevicePtr> AVFVideoDeviceManager::getVideoCaptureDevices()
		{
			if (captureDevices.empty()) {
				NSArray * devices = [AVCaptureDevice devicesWithMediaType: AVMediaTypeVideo];

				for (AVCaptureDevice * device in devices) {
					insertDevice(device, false);
				}
			}

			return captureDevices.devices();
		}

		std::set<VideoCaptureCapability> AVFVideoDeviceManager::getVideoCaptureCapabilities(const VideoDevice & device)
		{
			std::set<VideoCaptureCapability> capabilities;

			NSString * deviceID = [NSString stringWithUTF8String: device.getDescriptor().c_str()];
			AVCaptureDevice * dev = [AVCaptureDevice deviceWithUniqueID: deviceID];
			NSArray * devFormats = [dev formats];

			for (AVCaptureDeviceFormat * format in devFormats) {
				const CMVideoDimensions dimension = CMVideoFormatDescriptionGetDimensions(format.formatDescription);
				const FourCharCode fcc = CMFormatDescriptionGetMediaSubType(format.formatDescription);

				AVFrameRateRange * bestFrameRateRange = nil;

				for (AVFrameRateRange * range in format.videoSupportedFrameRateRanges) {
					if (range.maxFrameRate > bestFrameRateRange.maxFrameRate) {
						bestFrameRateRange = range;
					}
				}

				VideoCaptureCapability capability;
				capability.width = dimension.width;
				capability.height = dimension.height;
				capability.maxFPS = static_cast<int32_t>(bestFrameRateRange.maxFrameRate);
				capability.videoType = toVideoType(fcc);
				capabilities.insert(capability);
			}

			return capabilities;
		}

		void AVFVideoDeviceManager::insertDevice(AVCaptureDevice * device, bool notify)
		{
			std::string name([[device localizedName] cStringUsingEncoding: NSUTF8StringEncoding]);
			std::string desc([[device uniqueID] cStringUsingEncoding: NSUTF8StringEncoding]);

			auto captureDevice = std::make_shared<VideoDevice>(name, desc);

			captureDevices.insertDevice(captureDevice);

			// Update default device.
			AVCaptureDevice * defaultDevice = [AVCaptureDevice defaultDeviceWithMediaType: AVMediaTypeVideo];
			NSString * defaultID = [defaultDevice uniqueID];

			if ([defaultID isEqualToString: [device uniqueID]]) {
				setDefaultCaptureDevice(captureDevice);
			}

			if (notify) {
				notifyDeviceConnected(captureDevice);
			}
		}

		void AVFVideoDeviceManager::removeDevice(AVCaptureDevice * device, bool notify)
		{
			std::string desc([[device uniqueID] cStringUsingEncoding: NSUTF8StringEncoding]);

			auto predicate = [desc](const VideoDevicePtr & dev) { return desc == dev->getDescriptor(); };
			VideoDevicePtr removed = captureDevices.removeDevice(predicate);

			if (removed == nullptr) {
				std::string name([[device localizedName] cStringUsingEncoding: NSUTF8StringEncoding]);

				RTC_LOG(LS_WARNING) << "AVFoundation: Remove device [" << name.c_str() << "] failed";
				return;
			}

			// Update default device.
			AVCaptureDevice * defaultDevice = [AVCaptureDevice defaultDeviceWithMediaType: AVMediaTypeVideo];
			VideoDevicePtr defaultCapture = nullptr;

			if (defaultDevice) {
				std::string desc([[defaultDevice uniqueID] cStringUsingEncoding: NSUTF8StringEncoding]);

				auto predicate = [desc](const VideoDevicePtr & dev) { return desc == dev->getDescriptor(); };
				defaultCapture = captureDevices.findDevice(predicate);
			}

			setDefaultCaptureDevice(defaultCapture);

			if (notify) {
				notifyDeviceDisconnected(removed);
			}
		}

		void AVFVideoDeviceManager::onDeviceConnected(AVCaptureDevice * device)
		{
			if ([device hasMediaType: AVMediaTypeVideo]) {
				insertDevice(device, true);
			}
		}

		void AVFVideoDeviceManager::onDeviceDisconnected(AVCaptureDevice * device)
		{
			if ([device hasMediaType: AVMediaTypeVideo]) {
				removeDevice(device, true);
			}
		}

		webrtc::VideoType AVFVideoDeviceManager::toVideoType(const std::uint32_t & fourCC)
		{
			switch (fourCC) {
				case kCMPixelFormat_32ARGB:
					return webrtc::VideoType::kBGRA;

				case kCMPixelFormat_32BGRA:
					return webrtc::VideoType::kARGB;

				case kCMPixelFormat_24RGB:
					return webrtc::VideoType::kRGB24;

				case kCMPixelFormat_16LE565:
					return webrtc::VideoType::kRGB565;

				case kCMPixelFormat_422YpCbCr8:
					return webrtc::VideoType::kUYVY;

				case kCMPixelFormat_422YpCbCr8_yuvs:
					return webrtc::VideoType::kYUY2;

				case kCMVideoCodecType_JPEG_OpenDML:
					return webrtc::VideoType::kMJPEG;

				case kCVPixelFormatType_420YpCbCr8Planar:
				case kCVPixelFormatType_420YpCbCr8PlanarFullRange:
					return webrtc::VideoType::kI420;

				default:
					return webrtc::VideoType::kUnknown;
			}
		}
	}
}
