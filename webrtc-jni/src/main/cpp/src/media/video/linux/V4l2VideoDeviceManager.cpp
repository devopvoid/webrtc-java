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

#include "media/video/linux/V4l2VideoDeviceManager.h"
#include "Exception.h"

#include "rtc_base/logging.h"

#include <fcntl.h>
#include <linux/videodev2.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <unistd.h>

#define UDEV_SUBSYSTEM "video4linux"
#define UDEV_ADD "add"
#define UDEV_REMOVE "remove"
#define UDEV_CHANGE "change"

namespace jni
{
	namespace avdev
	{
		V4l2VideoDeviceManager::V4l2VideoDeviceManager()
		{
			udev = udev_new();

			if (!udev) {
				throw Exception("V4l2: Create udev failed");
			}

			getVideoCaptureDevices();

			running = true;

			thread = std::thread(&V4l2VideoDeviceManager::run, this);
		}

		V4l2VideoDeviceManager::~V4l2VideoDeviceManager()
		{
			running = false;

			if (thread.joinable()) {
				try {
					thread.join();
				}
				catch (const std::system_error & error) {
					RTC_LOG(LS_ERROR) << "Thread Join Error: " << error.what();
				}
			}

			udev_unref(udev);
		}

		std::set<VideoDevicePtr> V4l2VideoDeviceManager::getVideoCaptureDevices()
		{
			if (!captureDevices.empty()) {
				return captureDevices.devices();
			}

			udev_enumerate * enumerate = udev_enumerate_new(udev);
			udev_enumerate_add_match_subsystem(enumerate, UDEV_SUBSYSTEM);
			udev_enumerate_scan_devices(enumerate);

			udev_list_entry * udev_devices = udev_enumerate_get_list_entry(enumerate);
			udev_list_entry * dev_list_entry;
			v4l2_capability vcap;

			udev_list_entry_foreach(dev_list_entry, udev_devices) {
				const char * path = udev_list_entry_get_name(dev_list_entry);

				if (!path) {
					RTC_LOG(LS_ERROR) << "V4l2: Failed to get device sys path";
					continue;
				}

				udev_device * dev = udev_device_new_from_syspath(udev, path);

				if (!dev) {
					RTC_LOG(LS_ERROR) << "V4l2: Failed to get device from sys path: " << path;
					continue;
				}

				const char * node = udev_device_get_devnode(dev);
				bool error = false;

				int v4l2_fd = open(node, O_RDONLY);
				if (v4l2_fd < 0) {
					RTC_LOG(LS_ERROR) << "V4l2: Failed to open device: " << node;
					error = true;
				}
				if (ioctlDevice(v4l2_fd, VIDIOC_QUERYCAP, &vcap) == -1) {
					RTC_LOG(LS_ERROR) << "V4l2: Failed to query device caps: " << node;
					error = true;
				}
				if (!(vcap.capabilities & V4L2_CAP_VIDEO_CAPTURE)) {
					RTC_LOG(LS_ERROR) << "V4l2: Not a video capture device: " << node;
					error = true;
				}

				v4l2_fmtdesc fmt = { 0 };
				fmt.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;

				if (ioctlDevice(v4l2_fd, VIDIOC_ENUM_FMT, &fmt) == -1) {
					error = true;
				}

				close(v4l2_fd);

				if (!error) {
					const char * name = (const char *) vcap.card;

					addDevice(name, node);
				}

				udev_device_unref(dev);
			}

			udev_enumerate_unref(enumerate);

			return captureDevices.devices();
		}

		std::set<VideoCaptureCapability> V4l2VideoDeviceManager::getVideoCaptureCapabilities(const VideoDevice & device)
		{
			v4l2_capability vcap = { 0 };

			int v4l2_fd = open(device.getDescriptor().c_str(), O_RDONLY);
			if (v4l2_fd < 0) {
				throw Exception("V4l2: Failed to open device: %s", device.getName().c_str());
			}
			if (ioctlDevice(v4l2_fd, VIDIOC_QUERYCAP, &vcap) == -1) {
				throw Exception("V4l2: Failed to query device: %s", device.getName().c_str());
			}

			std::set<VideoCaptureCapability> capabilities;

			struct v4l2_fmtdesc fmt = { 0 };
			struct v4l2_frmsizeenum frameSize = { 0 };

			fmt.index = 0;
			fmt.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;

			while (ioctlDevice(v4l2_fd, VIDIOC_ENUM_FMT, &fmt) >= 0) {
				VideoCaptureCapability capability;
				capability.videoType = toVideoType(fmt.pixelformat);

				frameSize.pixel_format = fmt.pixelformat;
				frameSize.index = 0;

				while (ioctlDevice(v4l2_fd, VIDIOC_ENUM_FRAMESIZES, &frameSize) > -1) {
					if (frameSize.type == V4L2_FRMSIZE_TYPE_DISCRETE) {
						capability.width = frameSize.discrete.width;
						capability.height = frameSize.discrete.height;
					}
					else if (frameSize.type == V4L2_FRMSIZE_TYPE_STEPWISE) {
						capability.width = frameSize.stepwise.max_width;
						capability.height = frameSize.stepwise.max_height;
					}

					v4l2_frmivalenum frameInterval = { 0 };
					frameInterval.pixel_format = fmt.pixelformat;
					frameInterval.width = capability.width;
					frameInterval.height = capability.height;

					while (ioctlDevice(v4l2_fd, VIDIOC_ENUM_FRAMEINTERVALS, &frameInterval) == 0) {
						if (frameInterval.type == V4L2_FRMIVAL_TYPE_DISCRETE && frameInterval.discrete.numerator != 0) {
							capability.maxFPS = static_cast<int32_t>(static_cast<float>(frameInterval.discrete.denominator) / static_cast<float>(frameInterval.discrete.numerator));

							capabilities.emplace(capability);
						}
						else {
							capability.maxFPS = static_cast<int32_t>(static_cast<float>(frameInterval.stepwise.max.denominator) / static_cast<float>(frameInterval.stepwise.max.numerator));

							capabilities.emplace(capability);
						}

						frameInterval.index++;
					}

					frameSize.index++;
				}
				fmt.index++;
			}

			close(v4l2_fd);

			return capabilities;
		}

		void V4l2VideoDeviceManager::run()
		{
			udev_monitor * mon = udev_monitor_new_from_netlink(udev, "udev");

			if (!mon) {
				RTC_LOG(LS_ERROR) << "V4l2: Failed to init udev monitor";
				return;
			}

			udev_monitor_filter_add_match_subsystem_devtype(mon, UDEV_SUBSYSTEM, NULL);
			udev_monitor_enable_receiving(mon);

			pollfd items[1];
			items[0].fd = udev_monitor_get_fd(mon);
			items[0].events = POLLIN;

			while (running) {
				while (poll(items, 1, -1) > 0) {
					udev_device * dev = udev_monitor_receive_device(mon);

					if (!dev) {
						RTC_LOG(LS_ERROR) << "V4l2: No device received from udev monitor";
						continue;
					}

					const char * subsystem = udev_device_get_subsystem(dev);

					if (strcmp(subsystem, UDEV_SUBSYSTEM) != 0) {
						udev_device_unref(dev);
						continue;
					}

					const char * action = udev_device_get_action(dev);
					const char * node = udev_device_get_devnode(dev);
					const char * name = udev_device_get_property_value(dev, "ID_V4L_PRODUCT");

					if (strcmp(action, UDEV_ADD) == 0 && checkDevice(node)) {
						addDevice(name, node);
					}
					else if (strcmp(action, UDEV_REMOVE) == 0) {
						removeDevice(name, node);
					}

					udev_device_unref(dev);
				}
			}

			udev_monitor_unref(mon);
		}

		void V4l2VideoDeviceManager::addDevice(const std::string & name, const std::string & descriptor)
		{
			auto device = std::make_shared<VideoDevice>(name, descriptor);
			captureDevices.insertDevice(device);

			notifyDeviceConnected(device);
		}

		void V4l2VideoDeviceManager::removeDevice(const std::string & name, const std::string & descriptor)
		{
			auto predicate = [name, descriptor](const VideoDevicePtr & dev) {
				return dev->getName() == name && dev->getDescriptor() == descriptor;
			};

			VideoDevicePtr removed = captureDevices.removeDevice(predicate);

			if (removed) {
				notifyDeviceDisconnected(removed);
			}
		}

		bool V4l2VideoDeviceManager::checkDevice(const std::string & descriptor)
		{
			int v4l2_fd = open(descriptor.c_str(), O_RDONLY);
			if (v4l2_fd < 0) {
				return false;
			}

			struct v4l2_fmtdesc fmt = { 0 };
			fmt.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;

			bool error = false;

			if (ioctlDevice(v4l2_fd, VIDIOC_ENUM_FMT, &fmt) == -1) {
				error = true;
			}

			close(v4l2_fd);

			return !error;
		}

		int V4l2VideoDeviceManager::ioctlDevice(int fh, int request, void * arg)
		{
			int r;

			do {
				r = ioctl(fh, request, arg);
			}
			while (-1 == r && ((errno == EINTR) || (errno == EAGAIN) || (errno == ETIMEDOUT)));

			return r;
		}

		webrtc::VideoType V4l2VideoDeviceManager::toVideoType(const std::uint32_t & v4l2MediaType)
		{
			static const MediaFormatConfiguration mediaTypeMap[] = {
				{V4L2_PIX_FMT_YUV420, webrtc::VideoType::kI420},
				{V4L2_PIX_FMT_YUYV, webrtc::VideoType::kYUY2},
				{V4L2_PIX_FMT_UYVY, webrtc::VideoType::kUYVY},
				{V4L2_PIX_FMT_YUV420, webrtc::VideoType::kIYUV},
				{V4L2_PIX_FMT_RGB24, webrtc::VideoType::kRGB24},
				{V4L2_PIX_FMT_BGR32, webrtc::VideoType::kBGRA},
				{V4L2_PIX_FMT_RGB32, webrtc::VideoType::kARGB},
				{V4L2_PIX_FMT_MJPEG, webrtc::VideoType::kMJPEG},
				{V4L2_PIX_FMT_YVU420, webrtc::VideoType::kYV12}
			};

			for (const auto & mediaMapping : mediaTypeMap) {
				if (mediaMapping.v4l2MediaType == v4l2MediaType) {
					return mediaMapping.videoType;
				}
			}

			return webrtc::VideoType::kUnknown;
		}
	}
}
