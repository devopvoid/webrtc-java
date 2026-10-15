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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_VAAPI_H265_DECODER_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_VAAPI_H265_DECODER_H_

#include "media/video/codec/linux/H265Parser.h"
#include "media/video/codec/linux/VaapiDecodeSession.h"
#include "media/video/codec/linux/VaapiLibrary.h"

#include "api/video/encoded_image.h"
#include "api/video/video_rotation.h"
#include "api/video_codecs/video_decoder.h"

#include <va/va.h>
#include <va/va_dec_hevc.h>

#include <cstdint>
#include <span>
#include <string>
#include <vector>

namespace jni
{
	// Decodes H.265 Main with the GPU driver's VA-API decoder, as Intel and
	// AMD drivers provide it on Linux.
	//
	// VA-API leaves the parsing of the stream to the application: the
	// parameter sets and slice headers are parsed here, and the decoder keeps
	// the picture order counts, the reference picture sets and the decoded
	// picture buffer, the driver decoding the slice data. Decoding is
	// synchronous; pictures come out in output order, which is decoding order
	// for a stream without reordering, as real-time encoders send. The decoded
	// picture is copied from its surface in NV12, cropped to the conformance
	// window, and converted to I420, which is what WebRTC's frames hold.
	//
	// A stream it does not take, such as one with scaling lists, and anything
	// that fails return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE; a picture that
	// refers to one that is not there returns WEBRTC_VIDEO_CODEC_ERROR, and
	// decoding starts again with the next key frame.
	class VaapiH265Decoder : public webrtc::VideoDecoder
	{
		public:
			explicit VaapiH265Decoder(VaapiLibrary & library);
			~VaapiH265Decoder() override;

			using webrtc::VideoDecoder::Decode;

			bool Configure(const Settings & settings) override;
			int32_t Decode(const webrtc::EncodedImage & image, int64_t renderTimeMs) override;
			int32_t RegisterDecodeCompleteCallback(webrtc::DecodedImageCallback * callback) override;
			int32_t Release() override;
			DecoderInfo GetDecoderInfo() const override;
			const char * ImplementationName() const override;

		private:
			// What a decoded picture is kept for.
			struct Picture
			{
				int surface = -1;
				int32_t poc = 0;
				bool shortTerm = false;
				bool longTerm = false;
				bool waitingOutput = false;

				// Of the image it came in, for the frame that shows it.
				uint32_t rtpTimestamp = 0;
				int64_t ntpTimeMs = 0;
				int64_t renderTimeMs = 0;
				webrtc::VideoRotation rotation = webrtc::kVideoRotation_0;

				bool IsReference() const;
			};

			// A slice of the picture being decoded.
			struct Slice
			{
				H265SliceHeader header;
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

			// The picture order count of the picture (8.3.1), and the reference
			// picture set it marks the decoded picture buffer with (8.3.2).
			int32_t PictureOrderCount(const H265SliceHeader & header, bool noRaslOutput);
			Result ApplyReferencePictureSet(const H265SliceHeader & header, const H265Sps & sps, int32_t poc);

			// The reference picture lists of a slice (8.3.4), as surfaces.
			bool BuildReferenceLists(const H265SliceHeader & header, std::vector<int> & list0,
				std::vector<int> & list1) const;

			void FillPicture(VAPictureParameterBufferHEVC & parameters, const H265Sps & sps, const H265Pps & pps,
				const H265SliceHeader & header, int surface) const;
			bool FillSlice(VASliceParameterBufferHEVC & parameters, const Slice & slice, bool last) const;
			// The index in ReferenceFrames of the reference picture in the
			// surface: they are in the order of the decoded picture buffer.
			int ReferenceFrameIndex(int surface) const;

			bool Render(const VAPictureParameterBufferHEVC & picture,
				const std::vector<VASliceParameterBufferHEVC> & sliceParameters, const std::vector<Slice> & slices,
				int surface);

			// Hands pictures to WebRTC in output order until at most the given
			// number wait.
			bool Bump(uint32_t keep);
			bool Output(const Picture & picture);
			void RemoveUnused();

			bool CreateSession(const H265Sps & sps);
			void DestroySession();
			int FreeSurface() const;

		private:
			VaapiLibrary & library;
			const std::string implementationName;

			webrtc::DecodedImageCallback * callback;

			H265Parser parser;

			VaapiDecodeSession session;
			uint32_t codedWidth;
			uint32_t codedHeight;
			uint32_t maxDecPicBuffering;
			uint32_t maxNumReorder;
			// The conformance window of the stream.
			uint32_t cropX;
			uint32_t cropY;
			uint32_t width;
			uint32_t height;

			// The decoded picture buffer, and the surfaces of the pictures the
			// current one refers to, by the subset of its reference picture
			// set they are in.
			std::vector<Picture> dpb;
			std::vector<int> stCurrBefore;
			std::vector<int> stCurrAfter;
			std::vector<int> ltCurr;

			// prevTid0Pic of 8.3.1.
			int32_t prevTid0Poc;
			// Whether the RASL pictures of the IRAP picture before are skipped.
			bool skipRasl;

			// Whether a key frame has been decoded, and whether anything since
			// then failed for good.
			bool started;
			bool failed;
	};
}

#endif
