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

#include "media/video/codec/nvenc/NvencVideoEncoderFactory.h"
#include "media/video/codec/nvenc/NvencVideoEncoder.h"

#include "api/video_codecs/h264_profile_level_id.h"
#include "api/video_codecs/video_codec.h"
#include "modules/video_coding/codecs/h264/include/h264.h"

namespace jni
{
	std::unique_ptr<NvencVideoEncoderFactory> NvencVideoEncoderFactory::Create()
	{
		NvencLibrary * library = NvencLibrary::Get();

		if (library == nullptr) {
			return nullptr;
		}

		return std::unique_ptr<NvencVideoEncoderFactory>(new NvencVideoEncoderFactory(*library));
	}

	NvencVideoEncoderFactory::NvencVideoEncoderFactory(NvencLibrary & library) :
		library(library)
	{
	}

	std::vector<webrtc::SdpVideoFormat> NvencVideoEncoderFactory::GetSupportedFormats() const
	{
		std::vector<webrtc::SdpVideoFormat> formats;

		if (library.SupportsH264()) {
			formats.push_back(webrtc::CreateH264Format(webrtc::H264Profile::kProfileConstrainedBaseline,
				webrtc::H264Level::kLevel3_1, "1"));
			formats.push_back(webrtc::CreateH264Format(webrtc::H264Profile::kProfileBaseline,
				webrtc::H264Level::kLevel3_1, "1"));
		}
		if (library.SupportsAv1()) {
			formats.push_back(webrtc::SdpVideoFormat::AV1Profile0());
		}

		return formats;
	}

	std::unique_ptr<webrtc::VideoEncoder> NvencVideoEncoderFactory::Create(const webrtc::Environment & env,
		const webrtc::SdpVideoFormat & format)
	{
		return std::make_unique<NvencVideoEncoder>(library, webrtc::PayloadStringToCodecType(format.name), format);
	}
}
