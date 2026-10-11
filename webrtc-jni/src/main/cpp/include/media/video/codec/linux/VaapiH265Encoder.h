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
#include "media/video/codec/linux/VaapiLibrary.h"

#include "api/video/video_frame.h"
#include "api/video_codecs/sdp_video_format.h"
#include "api/video_codecs/video_codec.h"
#include "api/video_codecs/video_encoder.h"

#include <va/va.h>
#include <va/va_enc_hevc.h>

#include <cstdint>
#include <string>
#include <vector>

namespace jni
{
	// Encodes H.265 Main with the GPU driver's VA-API encoder, as Intel and
	// AMD drivers provide it on Linux. Experimental: it has not run on such a
	// GPU yet, and is used only where it is turned on.
	//
	// It works as VaapiH264Encoder does: synchronous, one reference frame,
	// P-frames only, the driver writing the parameter sets and slice headers
	// from the parameters it is given. The block sizes and coding tools are
	// those the driver says it takes, as FFmpeg's VA-API encoder chooses them.
	// The frame size is kept to a multiple of the smallest coding block, so
	// the coded picture is the frame and needs no cropping. There is no
	// software H.265 to fall back to: anything that fails, including a driver
	// that writes no parameter sets into key frames, is an error, upon which
	// WebRTC switches to the next codec it negotiated.
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
			bool CreateSession();
			void DestroySession();
			bool Upload(const webrtc::VideoFrame & frame);
			bool Submit(bool idr);
			bool ReadOutput(std::vector<uint8_t> & output);

			bool AddBuffer(VABufferType type, void * data, size_t size);
			bool AddMiscParameter(VAEncMiscParameterType type, const void * data, size_t size);
			void DestroyFrameBuffers();

			void FillSequence(VAEncSequenceParameterBufferHEVC & sequence) const;
			void FillPicture(VAEncPictureParameterBufferHEVC & picture, bool idr) const;
			void FillSlice(VAEncSliceParameterBufferHEVC & slice, bool idr) const;
			// The picture the current one refers to.
			VAPictureHEVC Reference() const;

			bool Check(VAStatus status, const char * operation) const;

		private:
			VaapiLibrary & library;
			const VaapiFunctions & va;
			const VAEntrypoint entrypoint;
			const std::string implementationName;

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

			VAConfigID config;
			VAContextID context;
			VASurfaceID inputSurface;
			// The frame being encoded, and the one it refers to.
			VASurfaceID reconstructed[2];
			VABufferID codedBuffer;
			std::vector<VABufferID> frameBuffers;

			webrtc::VideoCodec codecSettings;
			uint32_t widthInCtbs;
			uint32_t heightInCtbs;
			uint32_t bitrateBps;
			uint32_t framerate;
			bool ratesChanged;

			// The picture order count since the last IDR picture, and which of
			// the reconstructed surfaces holds the reference.
			int32_t pictureOrder;
			int current;
			bool referenceValid;

			webrtc::EncodedImageCallback * callback;

			EncoderOutputProcessor outputProcessor;
	};
}

#endif
