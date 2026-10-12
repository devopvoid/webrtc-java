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

	HardwareVideoEncoderFactory::HardwareVideoEncoderFactory(
		std::shared_ptr<const HardwareFactories> hardware,
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

		for (const auto & factory : *hardware) {
			for (const webrtc::SdpVideoFormat & format : factory->GetSupportedFormats()) {
				bool listed = std::any_of(formats.begin(), formats.end(), [&](const webrtc::SdpVideoFormat & other) {
					return other.IsSameCodec(format);
				});

				if (!listed) {
					formats.push_back(format);
				}
			}
		}

		return formats;
	}

	webrtc::VideoEncoderFactory::CodecSupport HardwareVideoEncoderFactory::QueryCodecSupport(
		const webrtc::SdpVideoFormat & format, std::optional<std::string> scalability_mode,
		std::optional<webrtc::Resolution> resolution) const
	{
		CodecSupport support = webrtc::VideoEncoderFactory::QueryCodecSupport(format, scalability_mode, resolution);

		if (!support.is_supported) {
			return support;
		}

		support.is_power_efficient = std::any_of(hardware->begin(), hardware->end(), [&](const auto & factory) {
			return factory->QueryCodecSupport(format, scalability_mode, resolution).is_supported;
		}) || software->QueryCodecSupport(format, scalability_mode, resolution).is_power_efficient;

		return support;
	}

	std::unique_ptr<webrtc::VideoEncoder> HardwareVideoEncoderFactory::Create(const webrtc::Environment & env,
		const webrtc::SdpVideoFormat & format)
	{
		std::unique_ptr<webrtc::VideoEncoder> encoder;

		if (Supports(*software, format)) {
			encoder = software->Create(env, format);
		}

		// Built from the back, so that the most preferred encoder comes first
		// and each one falls back to the chain behind it.
		for (auto factory = hardware->rbegin(); factory != hardware->rend(); ++factory) {
			if (!Supports(**factory, format)) {
				continue;
			}

			std::unique_ptr<webrtc::VideoEncoder> hardwareEncoder = (*factory)->Create(env, format);

			if (!hardwareEncoder) {
				continue;
			}

			encoder = encoder
				? std::make_unique<FallbackVideoEncoder>(std::move(hardwareEncoder), std::move(encoder))
				: std::move(hardwareEncoder);
		}

		return encoder;
	}
}
