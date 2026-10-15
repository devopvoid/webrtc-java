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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_VAAPI_H264_DECODER_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_VAAPI_H264_DECODER_H_

#include "media/video/codec/linux/H264Parser.h"
#include "media/video/codec/linux/VaapiDecodeSession.h"
#include "media/video/codec/linux/VaapiLibrary.h"

#include "api/video/encoded_image.h"
#include "api/video/video_rotation.h"
#include "api/video_codecs/video_decoder.h"

#include <va/va.h>

#include <cstdint>
#include <span>
#include <string>
#include <vector>

namespace jni
{
	// Decodes H.264 with the GPU driver's VA-API decoder, as Intel and AMD
	// drivers provide it on Linux.
	//
	// VA-API leaves the parsing of the stream to the application: the
	// parameter sets and slice headers are parsed here, and the decoder keeps
	// the picture order counts, the marking of reference pictures and the
	// reference picture lists, the driver decoding the slice data. Decoding is
	// synchronous; pictures come out in output order, as far as the VUI says
	// they are reordered, and in decoding order otherwise, which is the order
	// of real-time encoders. The decoded picture is cropped and converted to
	// I420, which is what WebRTC's frames hold.
	//
	// Interlaced streams, 4:2:2 and 4:4:4, more than 8 bits, slice groups and
	// SP and SI slices, and anything that fails, return
	// WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE, so that the software decoder takes
	// over; a picture that refers to one that is not there returns
	// WEBRTC_VIDEO_CODEC_ERROR, and decoding starts again with the next key
	// frame.
	class VaapiH264Decoder : public webrtc::VideoDecoder
	{
		public:
			explicit VaapiH264Decoder(VaapiLibrary & library);
			~VaapiH264Decoder() override;

			using webrtc::VideoDecoder::Decode;

			bool Configure(const Settings & settings) override;
			int32_t Decode(const webrtc::EncodedImage & image, int64_t renderTimeMs) override;
			int32_t RegisterDecodeCompleteCallback(webrtc::DecodedImageCallback * callback) override;
			int32_t Release() override;
			DecoderInfo GetDecoderInfo() const override;
			const char * ImplementationName() const override;

		private:
			// A decoded frame, as long as it is referenced or waits for output.
			struct Picture
			{
				int surface = -1;
				uint32_t frameNum = 0;
				int32_t topPoc = 0;
				int32_t bottomPoc = 0;
				bool shortTerm = false;
				bool longTerm = false;
				uint32_t longTermFrameIdx = 0;
				bool waitingOutput = false;

				// Of the image it came in, for the frame that shows it.
				uint32_t rtpTimestamp = 0;
				int64_t ntpTimeMs = 0;
				int64_t renderTimeMs = 0;
				webrtc::VideoRotation rotation = webrtc::kVideoRotation_0;

				int32_t Poc() const;
				bool IsReference() const;
			};

			// A slice of the picture being decoded.
			struct Slice
			{
				H264SliceHeader header;
				std::span<const uint8_t> nal;
			};

			enum class Result
			{
				kOk,
				// A reference picture is missing; a key frame starts again.
				kMissingReference,
				kFailed
			};

			Result DecodePicture(const std::vector<Slice> & slices, const webrtc::EncodedImage & image,
				int64_t renderTimeMs);

			// The picture order counts of the picture (8.2.1), top and bottom.
			void PictureOrderCount(const H264SliceHeader & header, const H264Sps & sps, int32_t & top,
				int32_t & bottom);
			// The marking of the reference pictures after the current one is
			// decoded (8.2.5), which becomes one itself unless it is not used
			// for reference.
			Result MarkReferences(const H264SliceHeader & header, const H264Sps & sps, Picture & current);

			// The reference picture lists of a slice (8.2.4), as surfaces, -1
			// where there is no picture.
			bool BuildReferenceLists(const H264SliceHeader & header, const H264Sps & sps, int32_t currentPoc,
				std::vector<int> & list0, std::vector<int> & list1) const;
			// PicNum (8-28) of a short-term reference picture.
			int32_t PicNum(const Picture & picture, uint32_t currentFrameNum, uint32_t maxFrameNum) const;

			VAPictureH264 ToVaPicture(const Picture & picture) const;
			const Picture * FindBySurface(int surface) const;

			// Hands pictures to WebRTC in output order until at most the given
			// number wait.
			bool Bump(uint32_t keep);
			bool Output(const Picture & picture);
			void RemoveUnused();

			int FreeSurface() const;

		private:
			VaapiLibrary & library;
			const std::string implementationName;

			webrtc::DecodedImageCallback * callback;

			H264Parser parser;
			VaapiDecodeSession session;

			uint32_t widthInMbs;
			uint32_t heightInMbs;
			uint32_t maxNumRefFrames;
			uint32_t maxNumReorder;
			uint32_t cropX;
			uint32_t cropY;
			uint32_t width;
			uint32_t height;

			std::vector<Picture> dpb;
			// MaxLongTermFrameIdx plus one, 0 for "no long-term frame indices".
			uint32_t maxLongTermFrameIdxPlus1;

			// The state the picture order count and frame_num of the next
			// picture derive from (8.2.1).
			int32_t prevPocMsb;
			int32_t prevPocLsb;
			uint32_t prevFrameNum;
			uint32_t prevFrameNumOffset;
			uint32_t prevRefFrameNum;
			// FrameNumOffset and PicOrderCntMsb of the current picture, and
			// whether it had a memory management control operation 5.
			uint32_t frameNumOffset;
			int32_t pocMsb;
			bool mmco5;

			// Whether a key frame has been decoded, and whether anything since
			// then failed for good.
			bool started;
			bool failed;
	};
}

#endif
