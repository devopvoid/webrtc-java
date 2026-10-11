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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_VT_AV1_DECODER_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_VT_AV1_DECODER_H_

#include "media/video/codec/macos/VTVideoDecoder.h"

#include "api/video/encoded_image.h"

#include <cstdint>
#include <span>
#include <vector>

namespace jni
{
	// Decodes AV1 profile 0, 8 bit, on the media engine of Macs that have an
	// AV1 decoder, Apple M3 and newer, through VideoToolbox.
	//
	// The session is created from the sequence header of a key frame, which
	// goes into the AV1 configuration box ("av1C") of the format description,
	// and again whenever a key frame brings one the session does not take.
	// The OBUs of a temporal unit go to VideoToolbox as one sample, without
	// temporal delimiters, as AV1 is stored in ISOBMFF.
	//
	// Whatever VideoToolbox cannot decode goes to dav1d, by returning
	// WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE: another profile or bit depth,
	// monochrome, a stream with spatial layers, and a decoder that keeps
	// failing.
	class VTAv1Decoder : public VTVideoDecoder
	{
		public:
			VTAv1Decoder();
			~VTAv1Decoder() override = default;

			using webrtc::VideoDecoder::Decode;

			bool Configure(const Settings & settings) override;
			int32_t Decode(const webrtc::EncodedImage & image, int64_t renderTimeMs) override;

		private:
			// Makes the format description of a stream from its sequence
			// header OBU, header and size field included. Returns null for a
			// stream the hardware decoder does not take.
			static CMVideoFormatDescriptionRef CreateFormat(std::span<const uint8_t> sequenceHeaderObu);

		private:
			// The sample, rebuilt for each frame.
			std::vector<uint8_t> sample;
	};
}

#endif
