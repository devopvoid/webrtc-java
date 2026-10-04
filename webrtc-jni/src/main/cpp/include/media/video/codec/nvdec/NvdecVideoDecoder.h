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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_NVDEC_VIDEO_DECODER_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_NVDEC_VIDEO_DECODER_H_

#include "media/video/codec/nvdec/NvdecLibrary.h"

#include "api/video/encoded_image.h"
#include "api/video/video_codec_type.h"
#include "api/video/video_rotation.h"
#include "api/video_codecs/video_decoder.h"

#include <cstdint>
#include <map>
#include <string>
#include <vector>

namespace jni
{
	// Decodes H.264 or VP9 profile 0 on the NVDEC engine of an NVIDIA GPU.
	//
	// NVDEC's own parser takes the encoded image, finds the pictures in it and
	// calls back in three steps: a sequence, which creates the decoder, a
	// picture to decode, and a picture to show. Decoding is synchronous: with
	// no display delay all of that happens inside Decode, on the thread that
	// called it. A decoded picture is in NV12 in GPU memory; it is copied to
	// system memory, cropped to the picture, and converted to I420, which is
	// what WebRTC's frames hold.
	//
	// Whatever NVDEC cannot decode, such as another profile or a size the GPU
	// does not take, and whatever fails, returns
	// WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE, so that the software decoder takes
	// over.
	class NvdecVideoDecoder : public webrtc::VideoDecoder
	{
		public:
			// The codec is H.264 or VP9.
			NvdecVideoDecoder(NvdecLibrary & library, webrtc::VideoCodecType codec);
			~NvdecVideoDecoder() override;

			using webrtc::VideoDecoder::Decode;

			bool Configure(const Settings & settings) override;
			int32_t Decode(const webrtc::EncodedImage & image, int64_t renderTimeMs) override;
			int32_t RegisterDecodeCompleteCallback(webrtc::DecodedImageCallback * callback) override;
			int32_t Release() override;
			DecoderInfo GetDecoderInfo() const override;
			const char * ImplementationName() const override;

		private:
			// What becomes of an encoded image once it is decoded.
			struct PendingFrame
			{
				uint32_t rtpTimestamp;
				int64_t ntpTimeMs;
				int64_t renderTimeMs;
				webrtc::VideoRotation rotation;
			};

			static int CUDAAPI OnSequence(void * decoder, CUVIDEOFORMAT * format);
			static int CUDAAPI OnDecode(void * decoder, CUVIDPICPARAMS * picture);
			static int CUDAAPI OnDisplay(void * decoder, CUVIDPARSERDISPINFO * info);

			// Each returns 0 to stop the parser, and a positive number to go
			// on; the sequence callback returns the number of decode surfaces.
			int HandleSequence(CUVIDEOFORMAT * format);
			int HandleDecode(CUVIDPICPARAMS * picture);
			int HandleDisplay(CUVIDPARSERDISPINFO * info);

			void DestroyDecoder();

			// Copies the picture at the device pointer into nv12, cropped.
			bool Download(unsigned long long devicePointer, unsigned int pitch);

		private:
			NvdecLibrary & library;
			const webrtc::VideoCodecType codec;
			const cudaVideoCodec cudaCodec;
			const std::string implementationName;

			webrtc::DecodedImageCallback * callback;

			CUcontext context;
			CUvideoparser parser;
			CUvideodecoder decoder;

			// Whether a key frame has made the decoder, and whether anything
			// since then failed for good.
			bool started;
			bool failed;

			// The picture within the decoded surface, which is larger.
			unsigned int surfaceHeight;
			int left;
			int top;
			int width;
			int height;

			// The decoded picture in system memory, NV12.
			std::vector<uint8_t> nv12;

			int64_t counter;
			std::map<int64_t, PendingFrame> pendingFrames;
	};
}

#endif
