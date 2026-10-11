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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_VAAPI_VIDEO_DECODER_FACTORY_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_VAAPI_VIDEO_DECODER_FACTORY_H_

#include "media/video/codec/linux/VaapiLibrary.h"

#include "api/environment/environment.h"
#include "api/video_codecs/sdp_video_format.h"
#include "api/video_codecs/video_decoder.h"
#include "api/video_codecs/video_decoder_factory.h"

#include <memory>
#include <vector>

namespace jni
{
	// Creates VA-API decoders on the GPUs of Intel and AMD, for VP9 profile 0
	// where the driver decodes it, the format WebRTC's software decoder
	// offers too.
	class VaapiVideoDecoderFactory : public webrtc::VideoDecoderFactory
	{
		public:
			// Returns a factory, or null if there is no VA-API driver, or one
			// that decodes no codec offered here.
			static std::unique_ptr<VaapiVideoDecoderFactory> Create();

			~VaapiVideoDecoderFactory() override = default;

			std::vector<webrtc::SdpVideoFormat> GetSupportedFormats() const override;
			std::unique_ptr<webrtc::VideoDecoder> Create(const webrtc::Environment & env,
				const webrtc::SdpVideoFormat & format) override;

		private:
			explicit VaapiVideoDecoderFactory(VaapiLibrary & library);

		private:
			VaapiLibrary & library;
	};
}

#endif
