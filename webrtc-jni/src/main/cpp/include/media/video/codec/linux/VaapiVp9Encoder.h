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
	// drivers provide it on Linux. Like hardware VP9 encoding on Windows, it
	// has not run on such a GPU yet, and is used only where it is turned on.
	//
	// It works as the H.265 one does: synchronous, one reference frame, inter
	// frames only, the driver writing the uncompressed frame header from the
	// parameters it is given, as FFmpeg's VA-API encoder sets them without
	// B-frames. Every frame refreshes all eight reference slots, so that the
	// next one can refer to it as the last frame whichever slot it names.
	// Anything that fails is an error, upon which libvpx takes over.
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
			bool Submit(bool keyFrame);

			void FillSequence(VAEncSequenceParameterBufferVP9 & sequence) const;
			void FillPicture(VAEncPictureParameterBufferVP9 & picture, bool keyFrame) const;

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

			// Which of the reconstructed surfaces holds the current frame.
			int current;
			bool referenceValid;

			webrtc::EncodedImageCallback * callback;

			EncoderOutputProcessor outputProcessor;
	};
}

#endif
