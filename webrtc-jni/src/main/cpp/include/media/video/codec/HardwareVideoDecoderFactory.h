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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_HARDWARE_VIDEO_DECODER_FACTORY_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_HARDWARE_VIDEO_DECODER_FACTORY_H_

#include "api/environment/environment.h"
#include "api/video/resolution.h"
#include "api/video_codecs/sdp_video_format.h"
#include "api/video_codecs/video_decoder.h"
#include "api/video_codecs/video_decoder_factory.h"

#include <memory>
#include <optional>
#include <vector>

namespace jni
{
	// Puts hardware decoders in front of software ones, the way
	// HardwareVideoEncoderFactory does with encoders: a codec is decoded by
	// the first hardware factory that has it, falling back to the next and
	// finally to the software decoder.
	class HardwareVideoDecoderFactory : public webrtc::VideoDecoderFactory
	{
		public:
			// The hardware factories. They hold nothing but what probing for
			// them found, so several factories, on any thread, can share them.
			using HardwareFactories = std::vector<std::unique_ptr<webrtc::VideoDecoderFactory>>;

			// The hardware factories are in order of preference.
			HardwareVideoDecoderFactory(std::shared_ptr<const HardwareFactories> hardware,
				std::unique_ptr<webrtc::VideoDecoderFactory> software);
			~HardwareVideoDecoderFactory() override = default;

			std::vector<webrtc::SdpVideoFormat> GetSupportedFormats() const override;
			std::unique_ptr<webrtc::VideoDecoder> Create(const webrtc::Environment & env,
				const webrtc::SdpVideoFormat & format) override;

			using webrtc::VideoDecoderFactory::QueryCodecSupport;

			// A supported format is power efficient when a hardware factory
			// supports it, since that factory's decoder is the one tried first.
			CodecSupport QueryCodecSupport(const webrtc::SdpVideoFormat & format, bool reference_scaling,
				std::optional<webrtc::Resolution> resolution) const override;

		private:
			const std::shared_ptr<const HardwareFactories> hardware;
			const std::unique_ptr<webrtc::VideoDecoderFactory> software;
	};

	// Returns the factories for the hardware decoders of the platform that are
	// available, in order of preference; none if the platform has none this
	// library supports, or no device that can decode. Defined per platform.
	std::vector<std::unique_ptr<webrtc::VideoDecoderFactory>> CreatePlatformHardwareVideoDecoderFactories();
}

#endif
