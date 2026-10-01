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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_NVENC_H264_ENCODER_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_NVENC_H264_ENCODER_H_

#include "media/video/codec/nvenc/NvencLibrary.h"

#include "api/video/video_frame.h"
#include "api/video_codecs/sdp_video_format.h"
#include "api/video_codecs/video_codec.h"
#include "api/video_codecs/video_encoder.h"
#include "common_video/h264/h264_bitstream_parser.h"
#include "modules/video_coding/codecs/h264/include/h264_globals.h"

#include <nvEncodeAPI.h>

#include <cstdint>
#include <string>
#include <vector>

namespace jni
{
	// Encodes H.264 with NVENC, the encoder of NVIDIA GPUs, on the primary
	// CUDA context of the device.
	//
	// Encoding is synchronous: with no B-frames and a low-latency preset,
	// NVENC returns each frame as soon as it is encoded, so frames go in and
	// out on the encoder thread. Frames are passed in system memory as NV12,
	// into an input buffer NVENC allocates. Anything that fails makes the
	// encoder give up, so that the next encoder in line takes over.
	class NvencH264Encoder : public webrtc::VideoEncoder
	{
		public:
			NvencH264Encoder(NvencLibrary & library, const webrtc::SdpVideoFormat & format);
			~NvencH264Encoder() override;

			int32_t InitEncode(const webrtc::VideoCodec * codecSettings, const Settings & settings) override;
			int32_t RegisterEncodeCompleteCallback(webrtc::EncodedImageCallback * callback) override;
			int32_t Release() override;
			int32_t Encode(const webrtc::VideoFrame & frame, const std::vector<webrtc::VideoFrameType> * frameTypes) override;
			void SetRates(const RateControlParameters & parameters) override;
			EncoderInfo GetEncoderInfo() const override;

		private:
			bool OpenSession();
			bool Configure();
			void ApplyRates();
			bool CopyToInput(const webrtc::VideoFrame & frame, uint32_t * pitch);
			void DestroySession();

		private:
			NvencLibrary & library;
			const NV_ENCODE_API_FUNCTION_LIST & api;
			const std::string implementationName;

			CUcontext context;
			void * encoder;
			NV_ENC_INPUT_PTR inputBuffer;
			NV_ENC_OUTPUT_PTR outputBuffer;

			NV_ENC_INITIALIZE_PARAMS initParams;
			NV_ENC_CONFIG config;

			webrtc::VideoCodec codecSettings;
			uint32_t bitrateBps;
			uint32_t framerate;
			uint64_t frameCount;

			webrtc::EncodedImageCallback * callback;

			webrtc::H264BitstreamParser bitstreamParser;
			webrtc::H264PacketizationMode packetizationMode;
	};
}

#endif
