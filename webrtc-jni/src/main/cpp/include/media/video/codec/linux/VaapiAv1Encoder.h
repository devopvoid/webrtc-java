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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_VAAPI_AV1_ENCODER_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_VAAPI_AV1_ENCODER_H_

#include "media/video/codec/EncoderOutputProcessor.h"
#include "media/video/codec/linux/VaapiEncodeSession.h"
#include "media/video/codec/linux/VaapiLibrary.h"
#include "media/video/codec/linux/VaapiTemporalLayers.h"

#include "api/video/video_frame.h"
#include "api/video_codecs/sdp_video_format.h"
#include "api/video_codecs/video_codec.h"
#include "api/video_codecs/video_encoder.h"
#include "modules/video_coding/svc/scalable_video_controller.h"

#include <va/va.h>
#include <va/va_enc_av1.h>

#include <array>
#include <cstdint>
#include <string>
#include <vector>

namespace jni
{
	// Encodes AV1 profile 0 with the GPU driver's VA-API encoder, as AMD
	// drivers provide it on Linux.
	//
	// It works as FFmpeg's VA-API encoder does without B-frames: synchronous,
	// one tile, inter frames that refer to one frame before them. Without
	// layers that is the frame just before; with temporal layers (L1T2, L1T3)
	// it is the one of the same layer or a lower one that WebRTC's structure
	// of the mode names, so that a receiver can do without the upper layers.
	// Each reference buffer of the structure is the reference slot of the
	// same number. The sequence and frame headers
	// are written here and handed to the driver as packed headers, with where
	// the quantizer, loop filter and CDEF parameters are in the frame header,
	// which the driver's rate control fills in. Spatial layers, and anything
	// that fails, are left to libaom.
	class VaapiAv1Encoder : public webrtc::VideoEncoder
	{
		public:
			VaapiAv1Encoder(VaapiLibrary & library, VAEntrypoint entrypoint, const webrtc::SdpVideoFormat & format);
			~VaapiAv1Encoder() override;

			int32_t InitEncode(const webrtc::VideoCodec * codecSettings, const Settings & settings) override;
			int32_t RegisterEncodeCompleteCallback(webrtc::EncodedImageCallback * callback) override;
			int32_t Release() override;
			int32_t Encode(const webrtc::VideoFrame & frame, const std::vector<webrtc::VideoFrameType> * frameTypes) override;
			void SetRates(const RateControlParameters & parameters) override;
			EncoderInfo GetEncoderInfo() const override;

		private:
			// Where the driver rewrites the frame header: the bit offsets of
			// base_q_idx, loop_filter_params() and cdef_params(), the size of
			// the latter, and the size of the whole frame header OBU.
			struct FrameHeaderLayout
			{
				uint32_t qindexOffset = 0;
				uint32_t loopFilterOffset = 0;
				uint32_t cdefOffset = 0;
				uint32_t cdefSize = 0;
				uint32_t size = 0;
			};

			// Reads which headers the driver takes and how it codes them.
			void QueryCapabilities();

			bool Submit(bool keyFrame, const VaapiTemporalLayers::Frame & frame);

			void WriteSequenceHeader(std::vector<uint8_t> & obu) const;
			void WriteFrameHeader(std::vector<uint8_t> & obu, FrameHeaderLayout & layout, bool keyFrame,
				const VaapiTemporalLayers::Frame & frame) const;
			// The seq_level_idx the stream's size, frame rate and bitrate fit.
			uint8_t Level() const;

		private:
			VaapiLibrary & library;
			const VAEntrypoint entrypoint;
			const std::string implementationName;
			VaapiEncodeSession session;

			bool initialized;
			webrtc::VideoCodec codecSettings;
			uint32_t bitrateBps;
			uint32_t framerate;
			bool ratesChanged;

			// The fixed length of obu_size the driver takes, in bytes, zero
			// where it takes no packed headers; and the TxMode it encodes.
			uint32_t obuSizeLength;
			uint32_t txMode;
			// The sequence header, kept for the key frames.
			std::vector<uint8_t> sequenceHeader;

			VaapiTemporalLayers layers;
			bool referenceValid;
			// The frames since the last key frame, whose low bits are the
			// order hint.
			uint32_t frameNumber;

			webrtc::EncodedImageCallback * callback;

			EncoderOutputProcessor outputProcessor;
	};
}

#endif
