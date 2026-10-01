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

#include "media/video/codec/HardwareVideoEncoderFactory.h"
#include "media/video/codec/FallbackVideoEncoder.h"

#include <algorithm>

namespace jni
{
	namespace
	{
		bool Supports(const webrtc::VideoEncoderFactory & factory, const webrtc::SdpVideoFormat & format)
		{
			const std::vector<webrtc::SdpVideoFormat> formats = factory.GetSupportedFormats();

			return std::any_of(formats.begin(), formats.end(), [&](const webrtc::SdpVideoFormat & supported) {
				return supported.IsSameCodec(format);
			});
		}
	}

	HardwareVideoEncoderFactory::HardwareVideoEncoderFactory(std::unique_ptr<webrtc::VideoEncoderFactory> hardware,
		std::unique_ptr<webrtc::VideoEncoderFactory> software) :
		hardware(std::move(hardware)),
		software(std::move(software))
	{
	}

	std::vector<webrtc::SdpVideoFormat> HardwareVideoEncoderFactory::GetSupportedFormats() const
	{
		// The software formats keep their order, so that hardware support
		// does not change which codec a peer connection prefers.
		std::vector<webrtc::SdpVideoFormat> formats = software->GetSupportedFormats();

		for (const webrtc::SdpVideoFormat & format : hardware->GetSupportedFormats()) {
			if (!Supports(*software, format)) {
				formats.push_back(format);
			}
		}

		return formats;
	}

	std::unique_ptr<webrtc::VideoEncoder> HardwareVideoEncoderFactory::Create(const webrtc::Environment & env,
		const webrtc::SdpVideoFormat & format)
	{
		std::unique_ptr<webrtc::VideoEncoder> hardwareEncoder;
		std::unique_ptr<webrtc::VideoEncoder> softwareEncoder;

		if (Supports(*hardware, format)) {
			hardwareEncoder = hardware->Create(env, format);
		}
		if (Supports(*software, format)) {
			softwareEncoder = software->Create(env, format);
		}

		if (hardwareEncoder && softwareEncoder) {
			return std::make_unique<FallbackVideoEncoder>(std::move(hardwareEncoder), std::move(softwareEncoder));
		}

		return hardwareEncoder ? std::move(hardwareEncoder) : std::move(softwareEncoder);
	}
}
