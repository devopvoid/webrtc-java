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
#include "media/video/codec/macos/VTVideoDecoderFactory.h"
#include "media/video/codec/macos/VTVideoEncoderFactory.h"

namespace jni
{
	std::vector<std::unique_ptr<webrtc::VideoEncoderFactory>> CreatePlatformHardwareVideoEncoderFactories()
	{
		std::vector<std::unique_ptr<webrtc::VideoEncoderFactory>> factories;

		// H.264 is encoded through VideoToolbox by the default encoders;
		// H.265 is the codec they lack.
		if (auto videoToolbox = VTVideoEncoderFactory::Create()) {
			factories.push_back(std::move(videoToolbox));
		}

		return factories;
	}

	std::vector<std::unique_ptr<webrtc::VideoDecoderFactory>> CreatePlatformHardwareVideoDecoderFactories()
	{
		std::vector<std::unique_ptr<webrtc::VideoDecoderFactory>> factories;

		// H.264 is decoded through VideoToolbox by the default decoders;
		// VP9 and AV1 are the codecs they decode in software, and H.265 one
		// they lack.
		if (auto videoToolbox = VTVideoDecoderFactory::Create()) {
			factories.push_back(std::move(videoToolbox));
		}

		return factories;
	}
}
