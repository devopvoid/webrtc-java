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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_VT_H265_DECODER_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_VT_H265_DECODER_H_

#include "media/video/codec/macos/VTVideoDecoder.h"

#include "api/video/encoded_image.h"

#include <cstdint>
#include <vector>

namespace jni
{
	// Decodes H.265 Main profile on the media engine of a Mac, through
	// VideoToolbox, and only there: there is no software decoder for H.265,
	// and the session is required to be a hardware one.
	//
	// WebRTC hands over Annex B NAL units. The session is created from the
	// VPS, SPS and PPS of a key frame, and again whenever a key frame brings
	// parameter sets the session does not take. The slices go to VideoToolbox
	// with length prefixes, as the format description has it, without the
	// parameter sets.
	class VTH265Decoder : public VTVideoDecoder
	{
		public:
			VTH265Decoder();
			~VTH265Decoder() override = default;

			using webrtc::VideoDecoder::Decode;

			bool Configure(const Settings & settings) override;
			int32_t Decode(const webrtc::EncodedImage & image, int64_t renderTimeMs) override;

		private:
			// The sample, rebuilt for each frame.
			std::vector<uint8_t> sample;
	};
}

#endif
