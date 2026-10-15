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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_VAAPI_AV1_DECODER_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_VAAPI_AV1_DECODER_H_

#include "media/video/codec/linux/Av1Parser.h"
#include "media/video/codec/linux/VaapiLibrary.h"

#include "api/video/encoded_image.h"
#include "api/video_codecs/video_decoder.h"

#include <va/va.h>
#include <va/va_dec_av1.h>

#include <array>
#include <cstdint>
#include <span>
#include <string>
#include <vector>

namespace jni
{
	// Decodes AV1 profile 0 in 8 bit 4:2:0 with the GPU driver's VA-API
	// decoder, as AMD drivers provide it on Linux.
	//
	// VA-API leaves the parsing of the stream to the application: the
	// sequence and frame headers are parsed here, and where each tile is; the
	// driver decodes the tiles. Decoding is synchronous. The decoded frame is
	// copied from its surface in NV12 and converted to I420, which is what
	// WebRTC's frames hold.
	//
	// Whatever it cannot decode, such as another profile or bit depth, spatial
	// layers, film grain, superres or an inter frame that changes the size,
	// and whatever fails, returns WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE, so
	// that dav1d takes over.
	class VaapiAv1Decoder : public webrtc::VideoDecoder
	{
		public:
			explicit VaapiAv1Decoder(VaapiLibrary & library);
			~VaapiAv1Decoder() override;

			using webrtc::VideoDecoder::Decode;

			bool Configure(const Settings & settings) override;
			int32_t Decode(const webrtc::EncodedImage & image, int64_t renderTimeMs) override;
			int32_t RegisterDecodeCompleteCallback(webrtc::DecodedImageCallback * callback) override;
			int32_t Release() override;
			DecoderInfo GetDecoderInfo() const override;
			const char * ImplementationName() const override;

		private:
			// A frame of a temporal unit: its header, and, unless it shows a
			// frame again, the data of its tile groups one after another and
			// its tiles, with their offsets into that data.
			struct Frame
			{
				Av1FrameHeader header;
				std::vector<uint8_t> tileData;
				std::vector<VASliceParameterBufferAV1> tiles;
			};

			// Parses the OBUs of one temporal unit into its frames, checking
			// that all of them can be decoded before any is; false where the
			// software decoder has to take over, which decodes the temporal
			// unit again, so that nothing may have been output by then.
			bool ParseTemporalUnit(std::span<const uint8_t> data, std::vector<Frame> & frames);
			// Takes a tile group of the frame in.
			bool AddTileGroup(std::span<const uint8_t> payload, Frame & frame);
			// Decodes and shows the frames.
			bool DecodeFrames(const std::vector<Frame> & frames, const webrtc::EncodedImage & image,
				int64_t renderTimeMs);

			// Creates the configuration, the context and the surfaces for
			// frames of the size.
			bool CreateSession(uint32_t width, uint32_t height);
			void DestroySession();

			// Decodes the frame into the surface.
			bool DecodeFrame(const Frame & frame, int surface);
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

			Av1Parser parser;

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
