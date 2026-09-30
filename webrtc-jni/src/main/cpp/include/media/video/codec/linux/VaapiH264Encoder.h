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

#include "media/video/codec/linux/VaapiLibrary.h"

#include "api/video/video_frame.h"
#include "api/video_codecs/sdp_video_format.h"
#include "api/video_codecs/video_codec.h"
#include "api/video_codecs/video_encoder.h"
#include "common_video/h264/h264_bitstream_parser.h"
#include "modules/video_coding/codecs/h264/include/h264_globals.h"

#include <va/va.h>
#include <va/va_enc_h264.h>

#include <cstdint>
#include <string>
#include <vector>

namespace jni
{
	// Encodes H.264 Constrained Baseline with the GPU driver's VA-API encoder,
	// as Intel and AMD drivers provide it on Linux.
	//
	// Encoding is synchronous, with one reference frame and P-frames only, so
	// each frame is encoded before Encode() returns. The driver writes the
	// parameter sets and slice headers itself from the parameters it is given.
	// A driver that leaves the parameter sets out of a key frame, which WebRTC
	// cannot send without them, makes the encoder give up, as does anything
	// else that fails, so that the software encoder takes over.
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
			bool CreateSession();
			void DestroySession();
			bool Upload(const webrtc::VideoFrame & frame);
			bool Submit(bool idr);
			bool ReadOutput(std::vector<uint8_t> & output);

			bool AddBuffer(VABufferType type, void * data, size_t size);
			bool AddMiscParameter(VAEncMiscParameterType type, const void * data, size_t size);
			void DestroyFrameBuffers();

			void FillSequence(VAEncSequenceParameterBufferH264 & sequence) const;
			void FillPicture(VAEncPictureParameterBufferH264 & picture, bool idr) const;
			void FillSlice(VAEncSliceParameterBufferH264 & slice, bool idr) const;

			bool Check(VAStatus status, const char * operation) const;

		private:
			VaapiLibrary & library;
			const VaapiFunctions & va;
			const std::string implementationName;

			VAConfigID config;
			VAContextID context;
			VASurfaceID inputSurface;
			// The frame being encoded, and the one it refers to.
			VASurfaceID reconstructed[2];
			VABufferID codedBuffer;
			std::vector<VABufferID> frameBuffers;

			webrtc::VideoCodec codecSettings;
			uint32_t widthInMbs;
			uint32_t heightInMbs;
			uint32_t bitrateBps;
			uint32_t framerate;
			bool ratesChanged;

			// The frame number within the current IDR period, and which of
			// the reconstructed surfaces holds the reference.
			uint32_t frameNum;
			uint32_t idrPicId;
			int current;
			bool referenceValid;

			webrtc::EncodedImageCallback * callback;

			webrtc::H264BitstreamParser bitstreamParser;
			webrtc::H264PacketizationMode packetizationMode;
	};
}

#endif
