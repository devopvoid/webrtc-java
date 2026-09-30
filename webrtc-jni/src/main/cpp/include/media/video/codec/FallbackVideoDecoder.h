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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_FALLBACK_VIDEO_DECODER_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_FALLBACK_VIDEO_DECODER_H_

#include "api/video/encoded_image.h"
#include "api/video_codecs/video_decoder.h"

#include <memory>
#include <optional>

namespace jni
{
	// Decodes with a hardware decoder, and switches to a software decoder of
	// the same codec when the hardware one fails to configure, or gives up
	// while decoding by returning WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE. The
	// switch is for good. The software decoder starts with the frame the
	// hardware one gave up on; if that is no key frame, it fails to decode
	// it, and the receiver asks the sender for a key frame.
	//
	// WebRTC has such a wrapper too, but it is not part of the WebRTC
	// library this library links.
	class FallbackVideoDecoder : public webrtc::VideoDecoder
	{
		public:
			FallbackVideoDecoder(std::unique_ptr<webrtc::VideoDecoder> hardware,
				std::unique_ptr<webrtc::VideoDecoder> software);
			~FallbackVideoDecoder() override = default;

			using webrtc::VideoDecoder::Decode;

			bool Configure(const Settings & settings) override;
			int32_t Decode(const webrtc::EncodedImage & image, int64_t renderTimeMs) override;
			int32_t RegisterDecodeCompleteCallback(webrtc::DecodedImageCallback * callback) override;
			int32_t Release() override;
			DecoderInfo GetDecoderInfo() const override;
			const char * ImplementationName() const override;

		private:
			// Configures the software decoder with the settings the hardware
			// one was given. Returns whether it is ready.
			bool StartSoftware();

			webrtc::VideoDecoder * Active() const;

		private:
			const std::unique_ptr<webrtc::VideoDecoder> hardware;
			const std::unique_ptr<webrtc::VideoDecoder> software;

			bool useSoftware;

			std::optional<Settings> settings;
			webrtc::DecodedImageCallback * callback;
	};
}

#endif
