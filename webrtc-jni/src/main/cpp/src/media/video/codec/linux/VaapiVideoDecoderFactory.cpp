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


#include "media/video/codec/linux/VaapiVideoDecoderFactory.h"
#include "media/video/codec/H264Format.h"
#include "media/video/codec/H265Format.h"
#include "media/video/codec/linux/VaapiAv1Decoder.h"
#include "media/video/codec/linux/VaapiH264Decoder.h"
#include "media/video/codec/linux/VaapiH265Decoder.h"
#include "media/video/codec/linux/VaapiVp9Decoder.h"

#include "api/video_codecs/video_codec.h"
#include "rtc_base/logging.h"

namespace jni
{
	std::unique_ptr<VaapiVideoDecoderFactory> VaapiVideoDecoderFactory::Create()
	{
		VaapiLibrary * library = VaapiLibrary::Get();

		if (library == nullptr) {
			return nullptr;
		}

		const bool h264 = library->Decodes(VAProfileH264High);
		const bool vp9 = library->Decodes(VAProfileVP9Profile0);
		const bool h265 = library->Decodes(VAProfileHEVCMain);
		const bool av1 = library->Decodes(VAProfileAV1Profile0);

		RTC_LOG(LS_INFO) << "VA-API (" << library->Vendor() << ") H.264 decoding: " << h264 << ", VP9 decoding: "
			<< vp9 << ", H.265 decoding: " << h265 << ", AV1 decoding: " << av1;

		if (!h264 && !vp9 && !h265 && !av1) {
			return nullptr;
		}

		return std::unique_ptr<VaapiVideoDecoderFactory>(
			new VaapiVideoDecoderFactory(*library, h264, vp9, h265, av1));
	}

	VaapiVideoDecoderFactory::VaapiVideoDecoderFactory(VaapiLibrary & library, bool h264, bool vp9, bool h265,
		bool av1) :
		library(library),
		h264(h264),
		vp9(vp9),
		h265(h265),
		av1(av1)
	{
	}

	std::vector<webrtc::SdpVideoFormat> VaapiVideoDecoderFactory::GetSupportedFormats() const
	{
		std::vector<webrtc::SdpVideoFormat> formats;

		if (h264) {
			formats = CreateH264HardwareDecoderFormats();
		}
		if (vp9) {
			formats.push_back(webrtc::SdpVideoFormat::VP9Profile0());
		}
		if (h265) {
			formats.push_back(CreateH265MainFormat());
		}
		if (av1) {
			formats.push_back(webrtc::SdpVideoFormat::AV1Profile0());
		}

		return formats;
	}

	std::unique_ptr<webrtc::VideoDecoder> VaapiVideoDecoderFactory::Create(const webrtc::Environment & env,
		const webrtc::SdpVideoFormat & format)
	{
		if (h264 && webrtc::PayloadStringToCodecType(format.name) == webrtc::kVideoCodecH264) {
			return std::make_unique<VaapiH264Decoder>(library);
		}
		if (h265 && format.IsSameCodec(CreateH265MainFormat())) {
			return std::make_unique<VaapiH265Decoder>(library);
		}
		if (vp9 && format.IsSameCodec(webrtc::SdpVideoFormat::VP9Profile0())) {
			return std::make_unique<VaapiVp9Decoder>(library);
		}
		if (av1 && format.IsSameCodec(webrtc::SdpVideoFormat::AV1Profile0())) {
			return std::make_unique<VaapiAv1Decoder>(library);
		}

		return nullptr;
	}
}
