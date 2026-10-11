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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_VT_VIDEO_ENCODER_FACTORY_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_VT_VIDEO_ENCODER_FACTORY_H_

#include "api/environment/environment.h"
#include "api/video_codecs/sdp_video_format.h"
#include "api/video_codecs/video_encoder.h"
#include "api/video_codecs/video_encoder_factory.h"

#include <memory>
#include <vector>

namespace jni
{
	// Creates the VideoToolbox encoders that WebRTC's own encoders for macOS
	// do not offer: H.265 in the Main profile, on Macs that encode it in
	// hardware. H.264 is encoded through VideoToolbox by the default encoders
	// already, and VideoToolbox has no encoder for VP8, VP9 or AV1.
	class VTVideoEncoderFactory : public webrtc::VideoEncoderFactory
	{
		public:
			// Returns a factory, or null if this Mac has no hardware encoder
			// for H.265.
			static std::unique_ptr<VTVideoEncoderFactory> Create();

			~VTVideoEncoderFactory() override = default;

			std::vector<webrtc::SdpVideoFormat> GetSupportedFormats() const override;
			std::unique_ptr<webrtc::VideoEncoder> Create(const webrtc::Environment & env,
				const webrtc::SdpVideoFormat & format) override;

		private:
			VTVideoEncoderFactory() = default;
	};
}

#endif
