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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_VAAPI_VP9_DECODER_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_VAAPI_VP9_DECODER_H_

#include "media/video/codec/linux/VaapiLibrary.h"
#include "media/video/codec/linux/Vp9FrameHeaderParser.h"

#include "api/video/encoded_image.h"
#include "api/video_codecs/video_decoder.h"

#include <va/va.h>
#include <va/va_dec_vp9.h>

#include <array>
#include <cstdint>
#include <span>
#include <string>
#include <vector>

namespace jni
{
	// Decodes VP9 profile 0 with the GPU driver's VA-API decoder, as Intel and
	// AMD drivers provide it on Linux.
	//
	// VA-API leaves the parsing of the stream to the application: the frame
	// headers are parsed here, the driver decodes the rest, the compressed
	// header with the probabilities included. Decoding is synchronous. The
	// decoded frame is copied from its surface in NV12 and converted to I420,
	// which is what WebRTC's frames hold.
	//
	// Whatever it cannot decode, such as another profile, spatial layers or
	// an inter frame that changes the size, and whatever fails, returns
	// WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE, so that libvpx takes over.
	class VaapiVp9Decoder : public webrtc::VideoDecoder
	{
		public:
			explicit VaapiVp9Decoder(VaapiLibrary & library);
			~VaapiVp9Decoder() override;

			using webrtc::VideoDecoder::Decode;

			bool Configure(const Settings & settings) override;
			int32_t Decode(const webrtc::EncodedImage & image, int64_t renderTimeMs) override;
			int32_t RegisterDecodeCompleteCallback(webrtc::DecodedImageCallback * callback) override;
			int32_t Release() override;
			DecoderInfo GetDecoderInfo() const override;
			const char * ImplementationName() const override;

		private:
			// Creates the configuration, the context and the surfaces for
			// frames of the size.
			bool CreateSession(uint32_t width, uint32_t height);
			void DestroySession();

			// Decodes one frame of the image into the surface.
			bool DecodeFrame(const Vp9FrameHeader & header, std::span<const uint8_t> frame, int surface);
			// Hands the frame in the surface to WebRTC.
			bool Output(int surface, const webrtc::EncodedImage & image, int64_t renderTimeMs);
			// A surface that no reference slot holds.
			int FreeSurface() const;

			bool Check(VAStatus status, const char * operation) const;

		private:
			VaapiLibrary & library;
			const VaapiFunctions & va;
			const std::string implementationName;

			webrtc::DecodedImageCallback * callback;

			Vp9FrameHeaderParser parser;

			VAConfigID config;
			VAContextID context;
			std::vector<VASurfaceID> surfaces;
			uint32_t width;
			uint32_t height;

			// The image a decoded surface is copied into, where the driver
			// copies surfaces at all.
			VAImage downloadImage;

			// The surface each of the eight reference slots holds, -1 for none.
			std::array<int, 8> slots;

			// Whether a key frame has been decoded, and whether anything since
			// then failed for good.
			bool started;
			bool failed;
	};
}

#endif
