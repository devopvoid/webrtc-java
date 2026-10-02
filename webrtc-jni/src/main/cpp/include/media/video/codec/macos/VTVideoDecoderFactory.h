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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_VT_VIDEO_DECODER_FACTORY_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_VT_VIDEO_DECODER_FACTORY_H_

#include "api/environment/environment.h"
#include "api/video_codecs/sdp_video_format.h"
#include "api/video_codecs/video_decoder.h"
#include "api/video_codecs/video_decoder_factory.h"

#include <memory>
#include <vector>

namespace jni
{
	// Creates the VideoToolbox decoders that WebRTC's own decoders for macOS
	// do not offer: VP9, in profile 0, the format the software factory
	// offers first. H.264 is decoded through VideoToolbox by the default
	// decoders already.
	class VTVideoDecoderFactory : public webrtc::VideoDecoderFactory
	{
		public:
			// Returns a factory, or null if this Mac has no hardware decoder
			// for VP9. The decoder VideoToolbox has for VP9 has to be
			// registered first; this does it, once for the process.
			static std::unique_ptr<VTVideoDecoderFactory> Create();

			~VTVideoDecoderFactory() override = default;

			std::vector<webrtc::SdpVideoFormat> GetSupportedFormats() const override;
			std::unique_ptr<webrtc::VideoDecoder> Create(const webrtc::Environment & env,
				const webrtc::SdpVideoFormat & format) override;

		private:
			VTVideoDecoderFactory() = default;
	};
}

#endif
