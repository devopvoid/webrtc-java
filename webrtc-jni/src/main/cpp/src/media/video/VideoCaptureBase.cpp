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

#include "media/video/VideoCaptureBase.h"

namespace jni
{
	VideoCaptureBase::VideoCaptureBase()
	{
		capability.width = static_cast<int32_t>(1280);
		capability.height = static_cast<int32_t>(720);
		capability.maxFPS = static_cast<int32_t>(30);
	}

	VideoCaptureBase::~VideoCaptureBase()
	{
		destroy();
	}

	void VideoCaptureBase::setDevice(const avdev::DevicePtr & device)
	{
		this->device = device;
	}

	void VideoCaptureBase::setVideoCaptureCapability(const webrtc::VideoCaptureCapability & capability)
	{
		this->capability = capability;
	}

	void VideoCaptureBase::setVideoSink(std::unique_ptr<webrtc::VideoSinkInterface<webrtc::VideoFrame>> sink)
	{
		this->sink = std::move(sink);
	}
}