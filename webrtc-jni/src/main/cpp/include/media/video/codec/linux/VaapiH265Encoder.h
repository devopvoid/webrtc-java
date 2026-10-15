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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_VAAPI_H265_ENCODER_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_VAAPI_H265_ENCODER_H_

#include "media/video/codec/EncoderOutputProcessor.h"
#include "media/video/codec/linux/NalUnitWriter.h"
#include "media/video/codec/linux/VaapiEncodeSession.h"
#include "media/video/codec/linux/VaapiLibrary.h"
#include "media/video/codec/linux/VaapiTemporalLayers.h"

#include "api/video/video_frame.h"
#include "api/video_codecs/sdp_video_format.h"
#include "api/video_codecs/video_codec.h"
#include "api/video_codecs/video_encoder.h"

#include <va/va.h>
#include <va/va_enc_hevc.h>

#include <array>
#include <cstdint>
#include <string>
#include <vector>

namespace jni
{
	// Encodes H.265 Main with the GPU driver's VA-API encoder, as Intel and
	// AMD drivers provide it on Linux. Tested with Intel's iHD and AMD's
	// radeonsi drivers.
	//
	// It works as VaapiH264Encoder does: synchronous, one reference frame,
	// P-frames only, and the encoder writing the parameter sets and slice
	// headers for a driver that takes them, as Intel's and AMD's do. A driver
	// that predicts only with both reference lists filled, as Intel's
	// low-power encoder, gets each P-frame as a B-frame whose two lists hold
	// the same previous picture ("low delay B"), as FFmpeg does. With
	// temporal layers (L1T2, L1T3), which need a driver that takes the slice
	// headers, a picture refers to the one of its layer or a lower one that
	// WebRTC's structure of the mode names, its slice NAL unit carries its
	// temporal id, and its reference picture set keeps the pictures of the
	// reference slots of its layer and below, so that a receiver without the
	// upper layers misses none of them. The block
	// sizes and coding tools are those the driver says it takes, as FFmpeg's
	// VA-API encoder chooses them. The frame size is kept to a multiple of the
	// smallest coding block; the coded picture is that aligned to 16 and at
	// least 384x128, which AMD's encoder needs, and cropped to the frame by
	// the conformance window of the SPS. There is no software H.265 to fall back to: anything that
	// fails, including a driver that writes no parameter sets into key
	// frames, is an error, upon which WebRTC switches to the next codec it
	// negotiated.
	class VaapiH265Encoder : public webrtc::VideoEncoder
	{
		public:
			VaapiH265Encoder(VaapiLibrary & library, VAEntrypoint entrypoint, const webrtc::SdpVideoFormat & format);
			~VaapiH265Encoder() override;

			int32_t InitEncode(const webrtc::VideoCodec * codecSettings, const Settings & settings) override;
			int32_t RegisterEncodeCompleteCallback(webrtc::EncodedImageCallback * callback) override;
			int32_t Release() override;
			int32_t Encode(const webrtc::VideoFrame & frame, const std::vector<webrtc::VideoFrameType> * frameTypes) override;
			void SetRates(const RateControlParameters & parameters) override;
			EncoderInfo GetEncoderInfo() const override;

		private:
			// What the driver says about the HEVC encoder it has.
			void QueryCapabilities();
			bool Submit(bool idr, const VaapiTemporalLayers::Frame & frame);

			void FillSequence(VAEncSequenceParameterBufferHEVC & sequence) const;
			void FillPicture(VAEncPictureParameterBufferHEVC & picture, bool idr,
				const VaapiTemporalLayers::Frame & frame) const;
			void FillSlice(VAEncSliceParameterBufferHEVC & slice, bool idr, const VaapiTemporalLayers::Frame & frame) const;

			// The VPS, SPS and PPS, and the slice segment header, matching
			// the parameters above. Each returns the bits it appended.
			size_t WriteParameterSets(std::vector<uint8_t> & output) const;
			size_t WriteSliceHeader(std::vector<uint8_t> & output, bool idr, const VaapiTemporalLayers::Frame & frame) const;
			void WriteProfileTierLevel(NalUnitWriter & writer) const;
			// diff_cu_qp_delta_depth.
			uint32_t CuQpDeltaDepth() const;
			// The pictures the reference slots hold, newest first, each once,
			// and whether the current frame refers to it: its reference
			// picture set.
			struct ReferencePicture
			{
				int slot;
				int32_t pictureOrder;
				bool used;
			};
			std::vector<ReferencePicture> ReferencePictureSet(const VaapiTemporalLayers::Frame & frame) const;
			VAPictureHEVC Picture(const ReferencePicture & reference) const;

		private:
			VaapiLibrary & library;
			const VAEntrypoint entrypoint;
			const std::string implementationName;
			VaapiEncodeSession session;

			// The sizes, in log2 of luma samples, and the tools the driver
			// takes.
			uint32_t log2CtbSize;
			uint32_t log2MinCbSize;
			uint32_t log2MinTbSize;
			uint32_t log2MaxTbSize;
			uint32_t maxTransformDepthInter;
			uint32_t maxTransformDepthIntra;
			bool amp;
			bool sao;
			bool signDataHiding;
			bool transformSkip;
			bool cuQpDelta;
			// The VA_ENC_PACKED_HEADER_* the driver takes from the encoder.
			uint32_t packedHeaders;
			// Whether P-frames are sent as B-frames with the same reference in
			// both lists.
			bool lowDelayB;

			bool initialized;
			webrtc::VideoCodec codecSettings;
			// The size of the coded picture.
			uint32_t codedWidth;
			uint32_t codedHeight;
			uint32_t widthInCtbs;
			uint32_t heightInCtbs;
			uint32_t bitrateBps;
			uint32_t framerate;
			bool ratesChanged;

			// The picture order count since the last IDR picture, the temporal
			// layers, and the picture order count and temporal id of the
			// picture each of their reference slots holds.
			int32_t pictureOrder;
			VaapiTemporalLayers layers;
			std::array<int32_t, 2> slotOrders;
			std::array<int, 2> slotTemporalIds;
			bool referenceValid;

			webrtc::EncodedImageCallback * callback;

			EncoderOutputProcessor outputProcessor;
	};
}

#endif
