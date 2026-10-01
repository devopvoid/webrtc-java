/*
 * Copyright 2026 Alex Andres
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

#include "media/video/codec/HardwareVideoDecoderFactory.h"
#include "media/video/codec/HardwareVideoEncoderFactory.h"
#include "media/video/codec/nvenc/NvencVideoEncoderFactory.h"
#include "media/video/codec/windows/MFVideoDecoderFactory.h"
#include "media/video/codec/windows/MFVideoEncoderFactory.h"

namespace jni
{
	std::vector<std::unique_ptr<webrtc::VideoEncoderFactory>> CreatePlatformHardwareVideoEncoderFactories()
	{
		std::vector<std::unique_ptr<webrtc::VideoEncoderFactory>> factories;

		// NVENC first: on an NVIDIA GPU it gives more control over latency
		// than the Media Foundation encoder of the same driver. When NVENC
		// fails, for example out of sessions, Media Foundation takes over.
		if (auto nvenc = NvencVideoEncoderFactory::Create()) {
			factories.push_back(std::move(nvenc));
		}
		if (auto mediaFoundation = MFVideoEncoderFactory::Create()) {
			factories.push_back(std::move(mediaFoundation));
		}

		return factories;
	}

	std::vector<std::unique_ptr<webrtc::VideoDecoderFactory>> CreatePlatformHardwareVideoDecoderFactories()
	{
		std::vector<std::unique_ptr<webrtc::VideoDecoderFactory>> factories;

		if (auto mediaFoundation = MFVideoDecoderFactory::Create()) {
			factories.push_back(std::move(mediaFoundation));
		}

		return factories;
	}
}
