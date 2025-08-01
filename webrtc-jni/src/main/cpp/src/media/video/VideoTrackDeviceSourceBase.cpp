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

#include "media/video/VideoTrackDeviceSourceBase.h"

namespace jni
{
	VideoTrackDeviceSourceBase::VideoTrackDeviceSourceBase()
	{
		capability.width = static_cast<int32_t>(1280);
		capability.height = static_cast<int32_t>(720);
		capability.maxFPS = static_cast<int32_t>(30);
	}

	VideoTrackDeviceSourceBase::~VideoTrackDeviceSourceBase()
	{
	}

	void VideoTrackDeviceSourceBase::setVideoDevice(const avdev::VideoDevicePtr & device)
	{
		this->device = device;
	}

	void VideoTrackDeviceSourceBase::setVideoCaptureCapability(const webrtc::VideoCaptureCapability & capability)
	{
		this->capability = capability;
	}
}