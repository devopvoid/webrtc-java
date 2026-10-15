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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_VAAPI_VP9_ENCODER_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_VAAPI_VP9_ENCODER_H_

#include "media/video/codec/EncoderOutputProcessor.h"
#include "media/video/codec/linux/VaapiEncodeSession.h"
#include "media/video/codec/linux/VaapiLibrary.h"
#include "media/video/codec/linux/VaapiTemporalLayers.h"

#include "api/video/video_frame.h"
#include "api/video_codecs/sdp_video_format.h"
#include "api/video_codecs/video_codec.h"
#include "api/video_codecs/video_encoder.h"

#include <va/va.h>
#include <va/va_enc_vp9.h>

#include <cstdint>
#include <string>
#include <vector>

namespace jni
{
	// Encodes VP9 profile 0 with the GPU driver's VA-API encoder, as Intel
	// drivers provide it on Linux.
	//
	// It works as the H.265 one does: synchronous, inter frames only, the
	// driver writing the uncompressed frame header from the parameters it is
	// given, as FFmpeg's VA-API encoder sets them without B-frames. Each frame
	// refers to one frame before it: without layers the frame just before;
	// with temporal layers (L1T2, L1T3) the one of the same layer or a lower
	// one that WebRTC's structure of the mode names, so that a receiver can do
	// without the upper layers. Spatial layers, and anything that fails, are
	// left to libvpx.
	class VaapiVp9Encoder : public webrtc::VideoEncoder
	{
		public:
			VaapiVp9Encoder(VaapiLibrary & library, VAEntrypoint entrypoint, const webrtc::SdpVideoFormat & format);
			~VaapiVp9Encoder() override;

			int32_t InitEncode(const webrtc::VideoCodec * codecSettings, const Settings & settings) override;
			int32_t RegisterEncodeCompleteCallback(webrtc::EncodedImageCallback * callback) override;
			int32_t Release() override;
			int32_t Encode(const webrtc::VideoFrame & frame, const std::vector<webrtc::VideoFrameType> * frameTypes) override;
			void SetRates(const RateControlParameters & parameters) override;
			EncoderInfo GetEncoderInfo() const override;

		private:
			bool Submit(bool keyFrame, const VaapiTemporalLayers::Frame & frame);

			void FillSequence(VAEncSequenceParameterBufferVP9 & sequence) const;
			void FillPicture(VAEncPictureParameterBufferVP9 & picture, bool keyFrame,
				const VaapiTemporalLayers::Frame & frame) const;

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

			VaapiTemporalLayers layers;
			bool referenceValid;

			webrtc::EncodedImageCallback * callback;

			EncoderOutputProcessor outputProcessor;
	};
}

#endif
