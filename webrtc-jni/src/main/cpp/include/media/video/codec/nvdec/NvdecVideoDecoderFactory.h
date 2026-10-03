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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_NVDEC_VIDEO_DECODER_FACTORY_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_NVDEC_VIDEO_DECODER_FACTORY_H_

#include "media/video/codec/nvdec/NvdecLibrary.h"

#include "api/environment/environment.h"
#include "api/video_codecs/sdp_video_format.h"
#include "api/video_codecs/video_decoder.h"
#include "api/video_codecs/video_decoder_factory.h"

#include <memory>
#include <vector>

namespace jni
{
	// Creates the NVDEC decoders that decode on an NVIDIA GPU, for H.264 and
	// VP9, whichever the GPU decodes. It offers them in the formats WebRTC's
	// software decoders offer too: H.264 in all its profiles, and VP9 in
	// profile 0.
	class NvdecVideoDecoderFactory : public webrtc::VideoDecoderFactory
	{
		public:
			// Returns a factory, or null if there is no NVIDIA driver, no CUDA
			// device, or a device that decodes neither codec.
			static std::unique_ptr<NvdecVideoDecoderFactory> Create();

			~NvdecVideoDecoderFactory() override = default;

			std::vector<webrtc::SdpVideoFormat> GetSupportedFormats() const override;
			std::unique_ptr<webrtc::VideoDecoder> Create(const webrtc::Environment & env,
				const webrtc::SdpVideoFormat & format) override;

		private:
			explicit NvdecVideoDecoderFactory(NvdecLibrary & library);

		private:
			NvdecLibrary & library;
	};
}

#endif
