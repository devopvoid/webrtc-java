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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_VAAPI_H264_ENCODER_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_VAAPI_H264_ENCODER_H_

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
#include <va/va_enc_h264.h>

#include <array>
#include <cstdint>
#include <string>
#include <vector>

namespace jni
{
	// Encodes H.264 Constrained Baseline with the GPU driver's VA-API encoder,
	// as Intel and AMD drivers provide it on Linux.
	//
	// Encoding is synchronous, with P-frames only, so each frame is encoded
	// before Encode() returns. The encoder writes the parameter sets and slice
	// headers itself and hands them to the driver as packed headers, as FFmpeg
	// does, since Intel's iHD driver writes none of its own; a driver that
	// takes no packed headers writes them from the parameters it is given.
	// Each frame refers to the frame just before it, or, with temporal layers
	// (L1T2, L1T3), which need a driver that takes the slice headers, to the
	// one of its layer or a lower one that WebRTC's structure of the mode
	// names: the top layer is not used for reference, frame_num may skip the
	// frames of the layers a receiver goes without, and each slice puts its
	// reference first in the list. A driver that leaves the parameter sets out
	// of a key frame, which WebRTC cannot send without them, makes the encoder
	// give up, as does anything else that fails, so that the software encoder
	// takes over.
	class VaapiH264Encoder : public webrtc::VideoEncoder
	{
		public:
			VaapiH264Encoder(VaapiLibrary & library, const webrtc::SdpVideoFormat & format);
			~VaapiH264Encoder() override;

			int32_t InitEncode(const webrtc::VideoCodec * codecSettings, const Settings & settings) override;
			int32_t RegisterEncodeCompleteCallback(webrtc::EncodedImageCallback * callback) override;
			int32_t Release() override;
			int32_t Encode(const webrtc::VideoFrame & frame, const std::vector<webrtc::VideoFrameType> * frameTypes) override;
			void SetRates(const RateControlParameters & parameters) override;
			EncoderInfo GetEncoderInfo() const override;

		private:
			// A frame being encoded: its layer, its frame_num and whether it is
			// used for reference.
			struct Picture
			{
				VaapiTemporalLayers::Frame frame;
				uint32_t frameNum = 0;
				bool reference = true;
			};

			bool Submit(bool idr, const Picture & picture);

			void FillSequence(VAEncSequenceParameterBufferH264 & sequence) const;
			void FillPicture(VAEncPictureParameterBufferH264 & parameters, bool idr, const Picture & picture) const;
			void FillSlice(VAEncSliceParameterBufferH264 & slice, bool idr, const Picture & picture) const;

			// The picture a reference slot holds, as VA-API names it.
			VAPictureH264 Reference(int slot) const;
			// The order count of a picture, as pic_order_cnt_type 2 derives it.
			static int32_t OrderCount(uint32_t frameNum, bool reference);

			// The SPS and PPS, and the header of the slice, matching the
			// parameters above. Each returns the bits it appended.
			size_t WriteParameterSets(std::vector<uint8_t> & output) const;
			size_t WriteSliceHeader(std::vector<uint8_t> & output, bool idr, const Picture & picture) const;

		private:
			VaapiLibrary & library;
			const std::string implementationName;
			VaapiEncodeSession session;

			// The VA_ENC_PACKED_HEADER_* the driver takes from the encoder.
			uint32_t packedHeaders;

			bool initialized;
			webrtc::VideoCodec codecSettings;
			uint32_t widthInMbs;
			uint32_t heightInMbs;
			uint32_t bitrateBps;
			uint32_t framerate;
			bool ratesChanged;

			// The frame_num of the last reference picture, and the temporal
			// layers with the frame_num of the picture each reference slot
			// holds.
			uint32_t previousReferenceFrameNum;
			uint32_t idrPicId;
			VaapiTemporalLayers layers;
			std::array<uint32_t, 2> slotFrameNums;
			bool referenceValid;

			webrtc::EncodedImageCallback * callback;

			EncoderOutputProcessor outputProcessor;
	};
}

#endif
