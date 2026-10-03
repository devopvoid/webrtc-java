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

#include "media/video/codec/nvdec/NvdecVideoDecoderFactory.h"
#include "media/video/codec/nvdec/NvdecVideoDecoder.h"

#include "api/video_codecs/video_codec.h"
#include "modules/video_coding/codecs/h264/include/h264.h"

namespace jni
{
	std::unique_ptr<NvdecVideoDecoderFactory> NvdecVideoDecoderFactory::Create()
	{
		NvdecLibrary * library = NvdecLibrary::Get();

		if (library == nullptr) {
			return nullptr;
		}

		return std::unique_ptr<NvdecVideoDecoderFactory>(new NvdecVideoDecoderFactory(*library));
	}

	NvdecVideoDecoderFactory::NvdecVideoDecoderFactory(NvdecLibrary & library) :
		library(library)
	{
	}

	std::vector<webrtc::SdpVideoFormat> NvdecVideoDecoderFactory::GetSupportedFormats() const
	{
		std::vector<webrtc::SdpVideoFormat> formats;

		if (library.SupportsH264()) {
			formats = webrtc::SupportedH264DecoderCodecs();
		}
		if (library.SupportsVp9()) {
			formats.push_back(webrtc::SdpVideoFormat::VP9Profile0());
		}

		return formats;
	}

	std::unique_ptr<webrtc::VideoDecoder> NvdecVideoDecoderFactory::Create(const webrtc::Environment & env,
		const webrtc::SdpVideoFormat & format)
	{
		return std::make_unique<NvdecVideoDecoder>(library, webrtc::PayloadStringToCodecType(format.name));
	}
}
