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

#include "media/video/codec/macos/VTVideoDecoderFactory.h"
#include "media/video/codec/macos/VTAv1Decoder.h"
#include "media/video/codec/macos/VTH265Decoder.h"
#include "media/video/codec/macos/VTVp9Decoder.h"
#include "media/video/codec/H265Format.h"

#include "api/video/video_codec_type.h"
#include "api/video_codecs/video_codec.h"
#include "rtc_base/logging.h"

#include <VideoToolbox/VideoToolbox.h>

namespace jni
{
	namespace
	{
		// Whether this Mac decodes the codec in hardware. The answer does not
		// change while the process runs, and is asked once per codec.
		bool HasHardwareDecoder(CMVideoCodecType codec, const char * name)
		{
			const bool supported = VTIsHardwareDecodeSupported(codec);

			RTC_LOG(LS_INFO) << "VideoToolbox hardware decoder for " << name << ": " << supported;

			return supported;
		}

		bool HasHardwareVp9Decoder()
		{
			// VideoToolbox has the VP9 decoder only after it is registered.
			static const bool available = [] {
				VTRegisterSupplementalVideoDecoderIfAvailable(kCMVideoCodecType_VP9);

				return HasHardwareDecoder(kCMVideoCodecType_VP9, "VP9");
			}();

			return available;
		}

		bool HasHardwareAv1Decoder()
		{
			static const bool available = HasHardwareDecoder(kCMVideoCodecType_AV1, "AV1");

			return available;
		}

		bool HasHardwareH265Decoder()
		{
			static const bool available = HasHardwareDecoder(kCMVideoCodecType_HEVC, "H.265");

			return available;
		}
	}

	std::unique_ptr<VTVideoDecoderFactory> VTVideoDecoderFactory::Create()
	{
		std::vector<webrtc::SdpVideoFormat> formats;

		if (HasHardwareVp9Decoder()) {
			formats.push_back(webrtc::SdpVideoFormat::VP9Profile0());
		}
		if (HasHardwareAv1Decoder()) {
			formats.push_back(webrtc::SdpVideoFormat::AV1Profile0());
		}
		if (HasHardwareH265Decoder()) {
			formats.push_back(CreateH265MainFormat());
		}

		if (formats.empty()) {
			return nullptr;
		}

		return std::unique_ptr<VTVideoDecoderFactory>(new VTVideoDecoderFactory(std::move(formats)));
	}

	VTVideoDecoderFactory::VTVideoDecoderFactory(std::vector<webrtc::SdpVideoFormat> formats) :
		formats(std::move(formats))
	{
	}

	std::vector<webrtc::SdpVideoFormat> VTVideoDecoderFactory::GetSupportedFormats() const
	{
		return formats;
	}

	std::unique_ptr<webrtc::VideoDecoder> VTVideoDecoderFactory::Create(const webrtc::Environment & env,
		const webrtc::SdpVideoFormat & format)
	{
		switch (webrtc::PayloadStringToCodecType(format.name)) {
			case webrtc::kVideoCodecVP9:
				return std::make_unique<VTVp9Decoder>();
			case webrtc::kVideoCodecAV1:
				return std::make_unique<VTAv1Decoder>();
			case webrtc::kVideoCodecH265:
				return std::make_unique<VTH265Decoder>();
			default:
				return nullptr;
		}
	}
}
