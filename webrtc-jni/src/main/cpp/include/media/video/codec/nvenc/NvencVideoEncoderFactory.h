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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_NVENC_VIDEO_ENCODER_FACTORY_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_NVENC_VIDEO_ENCODER_FACTORY_H_

#include "media/video/codec/nvenc/NvencLibrary.h"

#include "api/environment/environment.h"
#include "api/video_codecs/sdp_video_format.h"
#include "api/video_codecs/video_encoder.h"
#include "api/video_codecs/video_encoder_factory.h"

#include <memory>
#include <vector>

namespace jni
{
	// Creates NVENC encoders on an NVIDIA GPU. It offers H.264 in the
	// profiles WebRTC's software encoder offers too, and only with
	// packetization mode 1: mode 0 needs each NAL unit to fit a packet.
	class NvencVideoEncoderFactory : public webrtc::VideoEncoderFactory
	{
		public:
			// Returns a factory, or null if NVENC is not available.
			static std::unique_ptr<NvencVideoEncoderFactory> Create();

			~NvencVideoEncoderFactory() override = default;

			std::vector<webrtc::SdpVideoFormat> GetSupportedFormats() const override;
			std::unique_ptr<webrtc::VideoEncoder> Create(const webrtc::Environment & env,
				const webrtc::SdpVideoFormat & format) override;

		private:
			explicit NvencVideoEncoderFactory(NvencLibrary & library);

		private:
			NvencLibrary & library;
	};
}

#endif
