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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_FALLBACK_VIDEO_ENCODER_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_FALLBACK_VIDEO_ENCODER_H_

#include "api/video/video_frame.h"
#include "api/video_codecs/video_codec.h"
#include "api/video_codecs/video_encoder.h"

#include <memory>
#include <optional>
#include <vector>

namespace jni
{
	// Encodes with a hardware encoder, and switches to a software encoder of
	// the same codec when the hardware one fails to initialize, or gives up
	// while encoding by returning WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE. The
	// switch is for good; the software encoder starts with a key frame.
	//
	// WebRTC has such a wrapper too, but it is not part of the WebRTC
	// library this library links.
	class FallbackVideoEncoder : public webrtc::VideoEncoder
	{
		public:
			FallbackVideoEncoder(std::unique_ptr<webrtc::VideoEncoder> hardware,
				std::unique_ptr<webrtc::VideoEncoder> software);
			~FallbackVideoEncoder() override = default;

			int32_t InitEncode(const webrtc::VideoCodec * codecSettings, const Settings & settings) override;
			int32_t RegisterEncodeCompleteCallback(webrtc::EncodedImageCallback * callback) override;
			int32_t Release() override;
			int32_t Encode(const webrtc::VideoFrame & frame, const std::vector<webrtc::VideoFrameType> * frameTypes) override;
			void SetRates(const RateControlParameters & parameters) override;
			void OnPacketLossRateUpdate(float packetLossRate) override;
			void OnRttUpdate(int64_t rttMs) override;
			EncoderInfo GetEncoderInfo() const override;

		private:
			// Initializes the software encoder with the settings the hardware
			// one was given. Returns whether it is ready.
			bool StartSoftware();

			webrtc::VideoEncoder * Active() const;

		private:
			const std::unique_ptr<webrtc::VideoEncoder> hardware;
			const std::unique_ptr<webrtc::VideoEncoder> software;

			bool useSoftware;
			bool initialized;

			std::optional<webrtc::VideoCodec> codecSettings;
			std::optional<Settings> encoderSettings;
			std::optional<RateControlParameters> rates;
			webrtc::EncodedImageCallback * callback;
	};
}

#endif
