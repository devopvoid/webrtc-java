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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_MF_VIDEO_ENCODER_FACTORY_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_MF_VIDEO_ENCODER_FACTORY_H_

#include "api/environment/environment.h"
#include "api/video_codecs/sdp_video_format.h"
#include "api/video_codecs/video_encoder.h"
#include "api/video_codecs/video_encoder_factory.h"

#include <memory>
#include <vector>

namespace jni
{
	// Creates the Media Foundation hardware encoders of the GPU, for H.264 and
	// AV1, whichever the GPU has. It offers them in the formats WebRTC's
	// software encoders offer too: H.264 only with packetization mode 1, since
	// mode 0 needs each NAL unit to fit a packet, which hardware encoders
	// cannot be relied on to keep to, and AV1 in profile 0.
	class MFVideoEncoderFactory : public webrtc::VideoEncoderFactory
	{
		public:
			// Returns a factory, or null if there is no hardware H.264 or AV1
			// encoder on this system.
			static std::unique_ptr<MFVideoEncoderFactory> Create();

			~MFVideoEncoderFactory() override = default;

			std::vector<webrtc::SdpVideoFormat> GetSupportedFormats() const override;
			std::unique_ptr<webrtc::VideoEncoder> Create(const webrtc::Environment & env,
				const webrtc::SdpVideoFormat & format) override;

		private:
			MFVideoEncoderFactory(bool h264, bool av1);

		private:
			const bool h264;
			const bool av1;
	};
}

#endif
