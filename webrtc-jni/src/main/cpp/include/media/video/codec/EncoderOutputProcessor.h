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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_ENCODER_OUTPUT_PROCESSOR_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_ENCODER_OUTPUT_PROCESSOR_H_

#include "api/video/encoded_image.h"
#include "api/video/video_codec_type.h"
#include "api/video_codecs/sdp_video_format.h"
#include "api/video_codecs/video_encoder.h"
#include "common_video/h264/h264_bitstream_parser.h"
#include "modules/video_coding/codecs/h264/include/h264_globals.h"
#include "modules/video_coding/include/video_codec_interface.h"
#include "modules/video_coding/svc/scalable_video_controller_no_layering.h"

#include <cstdint>
#include <span>
#include <vector>

namespace jni
{
	// Turns what a hardware encoder produces for a frame into what WebRTC
	// sends, the same way for every hardware encoder: H.264 as Annex B NAL
	// units, AV1 as OBUs in the low overhead bitstream format.
	//
	// A receiver can start decoding only from a key frame that carries the
	// parameter sets, the SPS and PPS of H.264 or the sequence header of AV1.
	// Encoders put them in front of the first key frame, not necessarily in
	// front of every one, so they are kept and put back where missing. The
	// frame dependencies WebRTC needs for AV1 are those of a stream without
	// layers, which is all hardware encoders produce here.
	class EncoderOutputProcessor
	{
		public:
			EncoderOutputProcessor(webrtc::VideoCodecType codec, const webrtc::SdpVideoFormat & format);

			// Forgets the parameter sets, for a stream that starts over.
			void Reset();

			// Fills the image with the bitstream of one frame, and the codec
			// specific information WebRTC needs to send it. The caller sets
			// the size of the image before, which the AV1 frame dependencies
			// refer to, and its timestamps. The frame is a key
			// frame if the encoder says so or, for H.264, if it holds an IDR
			// slice. Returns false if the bitstream cannot be parsed, or a key
			// frame lacks parameter sets and there are none to put back.
			bool Process(std::span<const uint8_t> bitstream, bool keyFrame, webrtc::EncodedImage & image,
				webrtc::CodecSpecificInfo & info);

			// The quality scaling settings that suit the codec: the thresholds
			// of WebRTC's H.264 encoder, and none for AV1, whose QP is not
			// parsed from the bitstream.
			webrtc::VideoEncoder::ScalingSettings GetScalingSettings() const;

		private:
			bool ProcessH264(std::span<const uint8_t> bitstream, bool & keyFrame, std::vector<uint8_t> & output);
			bool ProcessAv1(std::span<const uint8_t> bitstream, bool keyFrame, std::vector<uint8_t> & output);

			void FillCodecSpecificInfo(const webrtc::EncodedImage & image, bool keyFrame,
				webrtc::CodecSpecificInfo & info);

		private:
			const webrtc::VideoCodecType codec;
			webrtc::H264PacketizationMode packetizationMode;

			// The SPS and PPS, or the sequence header OBU, last seen.
			std::vector<uint8_t> parameterSets;

			webrtc::H264BitstreamParser h264Parser;
			webrtc::ScalableVideoControllerNoLayering svcController;
	};
}

#endif
