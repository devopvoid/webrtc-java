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
#include "media/video/codec/FallbackVideoDecoder.h"

#include <algorithm>

namespace jni
{
	namespace
	{
		bool Supports(const webrtc::VideoDecoderFactory & factory, const webrtc::SdpVideoFormat & format)
		{
			const std::vector<webrtc::SdpVideoFormat> formats = factory.GetSupportedFormats();

			return std::any_of(formats.begin(), formats.end(), [&](const webrtc::SdpVideoFormat & supported) {
				return supported.IsSameCodec(format);
			});
		}
	}

	HardwareVideoDecoderFactory::HardwareVideoDecoderFactory(
		std::vector<std::unique_ptr<webrtc::VideoDecoderFactory>> hardware,
		std::unique_ptr<webrtc::VideoDecoderFactory> software) :
		hardware(std::move(hardware)),
		software(std::move(software))
	{
	}

	std::vector<webrtc::SdpVideoFormat> HardwareVideoDecoderFactory::GetSupportedFormats() const
	{
		// The software formats keep their order, so that hardware support
		// does not change what a peer connection negotiates.
		std::vector<webrtc::SdpVideoFormat> formats = software->GetSupportedFormats();

		for (const auto & factory : hardware) {
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

	std::unique_ptr<webrtc::VideoDecoder> HardwareVideoDecoderFactory::Create(const webrtc::Environment & env,
		const webrtc::SdpVideoFormat & format)
	{
		std::unique_ptr<webrtc::VideoDecoder> decoder;

		if (Supports(*software, format)) {
			decoder = software->Create(env, format);
		}

		// Built from the back, so that the most preferred decoder comes first
		// and each one falls back to the chain behind it.
		for (auto factory = hardware.rbegin(); factory != hardware.rend(); ++factory) {
			if (!Supports(**factory, format)) {
				continue;
			}

			std::unique_ptr<webrtc::VideoDecoder> hardwareDecoder = (*factory)->Create(env, format);

			if (!hardwareDecoder) {
				continue;
			}

			decoder = decoder
				? std::make_unique<FallbackVideoDecoder>(std::move(hardwareDecoder), std::move(decoder))
				: std::move(hardwareDecoder);
		}

		return decoder;
	}
}
