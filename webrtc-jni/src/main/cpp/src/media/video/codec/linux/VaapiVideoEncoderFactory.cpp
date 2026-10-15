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

#include "media/video/codec/linux/VaapiVideoEncoderFactory.h"
#include "media/video/codec/linux/VaapiAv1Encoder.h"
#include "media/video/codec/linux/VaapiH264Encoder.h"
#include "media/video/codec/linux/VaapiH265Encoder.h"
#include "media/video/codec/linux/VaapiVp9Encoder.h"
#include "media/video/codec/H265Format.h"

#include "api/video_codecs/h264_profile_level_id.h"
#include "api/video_codecs/video_codec.h"
#include "modules/video_coding/codecs/h264/include/h264.h"
#include "rtc_base/logging.h"

namespace jni
{
	std::unique_ptr<VaapiVideoEncoderFactory> VaapiVideoEncoderFactory::Create()
	{
		VaapiLibrary * library = VaapiLibrary::Get();

		if (library == nullptr) {
			return nullptr;
		}

		VAEntrypoint h265Entrypoint = VAEntrypointEncSlice;
		const bool h265 = library->EncodeEntrypoint(VAProfileHEVCMain, &h265Entrypoint);

		VAEntrypoint vp9Entrypoint = VAEntrypointEncSlice;
		const bool vp9 = library->EncodeEntrypoint(VAProfileVP9Profile0, &vp9Entrypoint);

		VAEntrypoint av1Entrypoint = VAEntrypointEncSlice;
		const bool av1 = library->EncodeEntrypoint(VAProfileAV1Profile0, &av1Entrypoint);

		RTC_LOG(LS_INFO) << "VA-API (" << library->Vendor() << ") H.265 encoding: " << h265 << ", VP9 encoding: "
			<< vp9 << ", AV1 encoding: " << av1;

		return std::unique_ptr<VaapiVideoEncoderFactory>(new VaapiVideoEncoderFactory(*library, h265, h265Entrypoint,
			vp9, vp9Entrypoint, av1, av1Entrypoint));
	}

	VaapiVideoEncoderFactory::VaapiVideoEncoderFactory(VaapiLibrary & library, bool h265, VAEntrypoint h265Entrypoint,
		bool vp9, VAEntrypoint vp9Entrypoint, bool av1, VAEntrypoint av1Entrypoint) :
		library(library),
		h265(h265),
		h265Entrypoint(h265Entrypoint),
		vp9(vp9),
		vp9Entrypoint(vp9Entrypoint),
		av1(av1),
		av1Entrypoint(av1Entrypoint)
	{
	}

	std::vector<webrtc::SdpVideoFormat> VaapiVideoEncoderFactory::GetSupportedFormats() const
	{
		std::vector<webrtc::SdpVideoFormat> formats = {
			webrtc::CreateH264Format(webrtc::H264Profile::kProfileConstrainedBaseline, webrtc::H264Level::kLevel3_1, "1"),
			webrtc::CreateH264Format(webrtc::H264Profile::kProfileBaseline, webrtc::H264Level::kLevel3_1, "1")
		};

		if (h265) {
			formats.push_back(CreateH265MainFormat());
		}
		if (vp9) {
			formats.push_back(webrtc::SdpVideoFormat::VP9Profile0());
		}
		if (av1) {
			formats.push_back(webrtc::SdpVideoFormat::AV1Profile0());
		}

		return formats;
	}

	std::unique_ptr<webrtc::VideoEncoder> VaapiVideoEncoderFactory::Create(const webrtc::Environment & env,
		const webrtc::SdpVideoFormat & format)
	{
		const webrtc::VideoCodecType codec = webrtc::PayloadStringToCodecType(format.name);

		if (codec == webrtc::kVideoCodecH265) {
			return std::make_unique<VaapiH265Encoder>(library, h265Entrypoint, format);
		}
		if (codec == webrtc::kVideoCodecVP9) {
			return std::make_unique<VaapiVp9Encoder>(library, vp9Entrypoint, format);
		}
		if (codec == webrtc::kVideoCodecAV1) {
			return std::make_unique<VaapiAv1Encoder>(library, av1Entrypoint, format);
		}

		return std::make_unique<VaapiH264Encoder>(library, format);
	}
}
