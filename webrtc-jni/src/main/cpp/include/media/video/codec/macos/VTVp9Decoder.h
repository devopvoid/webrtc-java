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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_VT_VP9_DECODER_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_VT_VP9_DECODER_H_

#include "media/video/codec/macos/VTVideoDecoder.h"

#include "api/video/encoded_image.h"
#include "modules/video_coding/utility/vp9_uncompressed_header_parser.h"

#include <cstdint>

namespace jni
{
	// Decodes VP9 profile 0 on the media engine or GPU of a Mac, through the
	// VP9 decoder VideoToolbox offers once it is registered.
	//
	// The decompression session needs the properties of the stream, so it is
	// created on the first key frame, from the header the key frame carries,
	// and again whenever a key frame changes the size or the range.
	//
	// Whatever VideoToolbox cannot decode in place goes to the software
	// decoder, by returning WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE: a profile
	// other than 0, a size outside what the hardware decodes, frames with
	// spatial layers, and a decoder that keeps failing.
	class VTVp9Decoder : public VTVideoDecoder
	{
		public:
			VTVp9Decoder();
			~VTVp9Decoder() override = default;

			using webrtc::VideoDecoder::Decode;

			bool Configure(const Settings & settings) override;
			int32_t Decode(const webrtc::EncodedImage & image, int64_t renderTimeMs) override;

		private:
			// Makes the format description of a stream from the header of a
			// key frame. Returns null for a stream the hardware decoder does
			// not take.
			static CMVideoFormatDescriptionRef CreateFormat(const webrtc::Vp9UncompressedHeader & header);
	};
}

#endif
