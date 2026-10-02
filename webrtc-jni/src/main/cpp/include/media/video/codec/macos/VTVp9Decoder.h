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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_VT_VP9_DECODER_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_VT_VP9_DECODER_H_

#include "api/video/encoded_image.h"
#include "api/video_codecs/video_decoder.h"
#include "modules/video_coding/utility/vp9_uncompressed_header_parser.h"

#include <CoreMedia/CoreMedia.h>
#include <CoreVideo/CoreVideo.h>
#include <VideoToolbox/VideoToolbox.h>

#include <cstdint>

namespace jni
{
	// Decodes VP9 profile 0 on the media engine or GPU of a Mac, through the
	// VP9 decoder VideoToolbox offers once it is registered.
	//
	// The decompression session needs the properties of the stream, so it is
	// created on the first key frame, from the header the key frame carries,
	// and again whenever a key frame changes the size or the range. Decoding
	// is synchronous: each frame comes out of VideoToolbox before Decode
	// returns. A frame is handed on as the CVPixelBuffer VideoToolbox made,
	// without a copy.
	//
	// Whatever VideoToolbox cannot decode in place goes to the software
	// decoder, by returning WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE: a profile
	// other than 0, a size outside what the hardware decodes, frames with
	// spatial layers, and a decoder that keeps failing.
	class VTVp9Decoder : public webrtc::VideoDecoder
	{
		public:
			VTVp9Decoder();
			~VTVp9Decoder() override;

			using webrtc::VideoDecoder::Decode;

			bool Configure(const Settings & settings) override;
			int32_t Decode(const webrtc::EncodedImage & image, int64_t renderTimeMs) override;
			int32_t RegisterDecodeCompleteCallback(webrtc::DecodedImageCallback * callback) override;
			int32_t Release() override;
			DecoderInfo GetDecoderInfo() const override;
			const char * ImplementationName() const override;

		private:
			// What a session is created for. A key frame that differs from it
			// needs a new format description, and often a new session.
			struct StreamConfig
			{
				int width = 0;
				int height = 0;
				bool fullRange = false;
				webrtc::Vp9ColorSpace colorSpace = webrtc::Vp9ColorSpace::CS_UNKNOWN;

				bool operator==(const StreamConfig & other) const;
			};

			// The result of decoding one frame, filled in by the callback of
			// the session.
			struct Output
			{
				OSStatus status = noErr;
				CVImageBufferRef image = nullptr;
			};

			static void OnOutput(void * decoder, void * frame, OSStatus status, VTDecodeInfoFlags flags,
				CVImageBufferRef image, CMTime presentationTime, CMTime duration);

			// Reads the stream properties from a key frame header. Returns
			// false for a stream the hardware decoder does not take.
			static bool ReadConfig(const webrtc::Vp9UncompressedHeader & header, StreamConfig & config);

			// Makes sure there is a session for the stream, creating or
			// replacing it where the stream changed.
			bool EnsureSession(const StreamConfig & config);
			bool CreateSession(const StreamConfig & config, CMVideoFormatDescriptionRef format);
			void DestroySession();

			// Decodes one encoded frame as a single sample.
			OSStatus DecodeSample(const webrtc::EncodedImage & image, Output & output);

			// Counts a failure and tells WebRTC what to do about it.
			int32_t Fail(OSStatus status);

			void Deliver(const webrtc::EncodedImage & image, int64_t renderTimeMs, CVImageBufferRef pixels);

		private:
			webrtc::DecodedImageCallback * callback;

			VTDecompressionSessionRef session;
			CMVideoFormatDescriptionRef format;
			StreamConfig active;

			// Set after a failure, until the next key frame.
			bool requireKeyFrame;
			int consecutiveErrors;
			int64_t sampleCount;
	};
}

#endif
