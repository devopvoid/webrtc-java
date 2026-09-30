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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_HARDWARE_VIDEO_ENCODER_FACTORY_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_HARDWARE_VIDEO_ENCODER_FACTORY_H_

#include "api/environment/environment.h"
#include "api/video_codecs/sdp_video_format.h"
#include "api/video_codecs/video_encoder.h"
#include "api/video_codecs/video_encoder_factory.h"

#include <memory>
#include <vector>

namespace jni
{
	// Puts hardware encoders in front of software ones. A codec is encoded by
	// the first hardware factory that has it, inside a wrapper that switches
	// to the next hardware encoder, and finally to the software one, when an
	// encoder fails to initialize or gives up while encoding, for example once
	// a GPU runs out of encoder sessions. A codec only the software factory
	// has is encoded by that one.
	class HardwareVideoEncoderFactory : public webrtc::VideoEncoderFactory
	{
		public:
			// The hardware factories are in order of preference.
			HardwareVideoEncoderFactory(std::vector<std::unique_ptr<webrtc::VideoEncoderFactory>> hardware,
				std::unique_ptr<webrtc::VideoEncoderFactory> software);
			~HardwareVideoEncoderFactory() override = default;

			std::vector<webrtc::SdpVideoFormat> GetSupportedFormats() const override;
			std::unique_ptr<webrtc::VideoEncoder> Create(const webrtc::Environment & env,
				const webrtc::SdpVideoFormat & format) override;

		private:
			const std::vector<std::unique_ptr<webrtc::VideoEncoderFactory>> hardware;
			const std::unique_ptr<webrtc::VideoEncoderFactory> software;
	};

	// Returns the factories for the hardware encoders of the platform that are
	// available, in order of preference; none if the platform has none this
	// library supports, or no device that can encode. Defined per platform.
	std::vector<std::unique_ptr<webrtc::VideoEncoderFactory>> CreatePlatformHardwareVideoEncoderFactories();
}

#endif
