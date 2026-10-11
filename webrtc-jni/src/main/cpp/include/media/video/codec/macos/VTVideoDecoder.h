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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_VT_VIDEO_DECODER_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_VT_VIDEO_DECODER_H_

#include "api/video/encoded_image.h"
#include "api/video_codecs/video_decoder.h"

#include <CoreMedia/CoreMedia.h>
#include <CoreVideo/CoreVideo.h>
#include <VideoToolbox/VideoToolbox.h>

#include <cstdint>
#include <span>

namespace jni
{
	// What the VideoToolbox decoders of the codecs have in common: a
	// decompression session on the media engine of the Mac, made for the
	// format description of the stream, which the decoder of each codec reads
	// from its key frames.
	//
	// Decoding is synchronous: each frame comes out of VideoToolbox before
	// Decode returns. A frame is handed on as the CVPixelBuffer VideoToolbox
	// made, without a copy, in 8 bit 4:2:0 and in the range of the stream.
	//
	// The session is made in hardware only, and checked to be. Where it cannot
	// be, or the decoder keeps failing, a codec that has a software decoder
	// goes to it, by returning WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE. One that
	// has none, H.265, is never decoded in software: its frames fail, and
	// WebRTC asks for key frames, until the hardware decodes again.
	class VTVideoDecoder : public webrtc::VideoDecoder
	{
		public:
			~VTVideoDecoder() override;

			int32_t RegisterDecodeCompleteCallback(webrtc::DecodedImageCallback * callback) override;
			int32_t Release() override;
			DecoderInfo GetDecoderInfo() const override;
			const char * ImplementationName() const override;

		protected:
			// The name is reported as the implementation. With a software
			// fallback, a decoder that keeps failing gives way to it.
			VTVideoDecoder(const char * name, bool softwareFallback);

			// Makes sure there is a session for the format, which this takes
			// over, keeping the session where the stream did not change, or
			// changed in a way the session accepts. Returns false if there is
			// no session for it, which leaves none.
			bool SetFormat(CMVideoFormatDescriptionRef format);

			bool HasSession() const;

			// The size of the format the session decodes.
			CMVideoDimensions GetDimensions() const;

			// Decodes one sample, in the form the format description says,
			// and hands the picture to WebRTC with the timing of the image.
			int32_t DecodeSample(std::span<const uint8_t> sample, const webrtc::EncodedImage & image,
				int64_t renderTimeMs);

			// What to return for a frame that cannot be decoded.
			int32_t Fail(OSStatus status);

			// What to return when the hardware cannot take a stream at all.
			int32_t Unsupported();

			// Whether the decoder needs a key frame before it can go on, once
			// the session or a frame failed.
			bool requireKeyFrame;

		private:
			// The result of decoding one frame, filled in by the callback of
			// the session.
			struct Output
			{
				OSStatus status = noErr;
				CVImageBufferRef image = nullptr;
			};

			static void OnOutput(void * decoder, void * frame, OSStatus status, VTDecodeInfoFlags flags,
				CVImageBufferRef image, CMTime presentationTime, CMTime duration);

			bool CreateSession(CMVideoFormatDescriptionRef newFormat);
			void DestroySession();

			void Deliver(const webrtc::EncodedImage & image, int64_t renderTimeMs, CVImageBufferRef pixels);

		private:
			const char * const name;
			const bool softwareFallback;

			webrtc::DecodedImageCallback * callback;

			VTDecompressionSessionRef session;
			CMVideoFormatDescriptionRef format;
			bool fullRange;

			int consecutiveErrors;
			int64_t sampleCount;
	};
}

#endif
