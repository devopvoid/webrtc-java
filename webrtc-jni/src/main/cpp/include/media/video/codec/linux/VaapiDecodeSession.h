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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_VAAPI_DECODE_SESSION_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_VAAPI_DECODE_SESSION_H_

#include "media/video/codec/linux/VaapiLibrary.h"

#include "api/scoped_refptr.h"
#include "api/video/i420_buffer.h"

#include <va/va.h>

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace jni
{
	// What every VA-API decoder of a codec whose stream the decoder parses
	// needs: a configuration and a context with the surfaces the decoded
	// pictures go into, decoding a picture from the parameter and data
	// buffers the codec's decoder fills in, and copying a decoded picture out
	// as I420, which is what WebRTC's frames hold.
	//
	// Used by the H.264, H.265 and AV1 decoders; the VP9 one keeps its own,
	// older copy of the same.
	class VaapiDecodeSession
	{
		public:
			// A parameter or data buffer of a picture.
			struct Buffer
			{
				VABufferType type;
				const void * data;
				size_t size;
			};

			// The name says whose operations failed, in the log.
			VaapiDecodeSession(VaapiLibrary & library, std::string name);
			~VaapiDecodeSession();

			VaapiDecodeSession(const VaapiDecodeSession &) = delete;
			VaapiDecodeSession & operator=(const VaapiDecodeSession &) = delete;

			// Creates the session for 4:2:0 pictures of the given size, which
			// is the coded size, with the given number of surfaces.
			bool Create(VAProfile profile, uint32_t width, uint32_t height, uint32_t surfaceCount);
			void Destroy();

			bool IsCreated() const;
			uint32_t Width() const;
			uint32_t Height() const;
			int SurfaceCount() const;
			VASurfaceID Surface(int index) const;

			// Decodes a picture into the surface from the buffers, in the
			// order given, and waits for it.
			bool Decode(int surface, const std::vector<Buffer> & buffers);
			// The region of the decoded picture in the surface, as I420; null
			// if it cannot be read.
			webrtc::scoped_refptr<webrtc::I420Buffer> Download(int surface, uint32_t x, uint32_t y, uint32_t width,
				uint32_t height);

			bool Check(VAStatus status, const char * operation) const;

		private:
			VaapiLibrary & library;
			const VaapiFunctions & va;
			const std::string name;

			VAConfigID config;
			VAContextID context;
			std::vector<VASurfaceID> surfaces;
			uint32_t width;
			uint32_t height;

			// The image a decoded surface is copied into, where the driver
			// copies surfaces at all.
			VAImage downloadImage;
	};
}

#endif
