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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_VAAPI_ENCODE_SESSION_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_VAAPI_ENCODE_SESSION_H_

#include "media/video/codec/linux/VaapiLibrary.h"

#include "api/video/video_frame.h"

#include <va/va.h>

#include <cstdint>
#include <string>
#include <vector>

namespace jni
{
	// What every VA-API encoder of a single codec needs: a configuration and
	// a context with an input surface and two reconstructed ones (the frame
	// being encoded and the one it refers to), the output buffer, the
	// parameter buffers of a frame, uploading a frame as NV12, encoding it
	// and reading what came out. The codec's encoder fills in the parameters.
	//
	// Used by the H.265 and VP9 encoders; the H.264 one keeps its own, older
	// copy of the same.
	class VaapiEncodeSession
	{
		public:
			// The name says whose operations failed, in the log.
			VaapiEncodeSession(VaapiLibrary & library, std::string name);
			~VaapiEncodeSession();

			VaapiEncodeSession(const VaapiEncodeSession &) = delete;
			VaapiEncodeSession & operator=(const VaapiEncodeSession &) = delete;

			// Creates the session for 4:2:0 at constant bitrate, for pictures
			// of the given size, in surfaces of the given size, which is that
			// of the picture aligned as the codec needs, and with the
			// VA_ENC_PACKED_HEADER_* the encoder hands the driver. Mesa takes
			// the size of the coded picture from the context, so the context
			// has the picture's.
			bool Create(VAProfile profile, VAEntrypoint entrypoint, uint32_t pictureWidth, uint32_t pictureHeight,
				uint32_t surfaceWidth, uint32_t surfaceHeight, uint32_t packedHeaders = VA_ENC_PACKED_HEADER_NONE);
			void Destroy();

			VASurfaceID InputSurface() const;
			VASurfaceID Reconstructed(int index) const;
			VABufferID CodedBuffer() const;

			// Puts the frame into the input surface as NV12, at the given size.
			// A frame up to tolerance pixels larger is cropped, which needs no
			// copy; one of another size, which raced with a change of size, is
			// scaled.
			bool Upload(const webrtc::VideoFrame & frame, int width, int height, int tolerance);

			bool AddBuffer(VABufferType type, void * data, size_t size);
			bool AddMiscParameter(VAEncMiscParameterType type, const void * data, size_t size);
			// A header the encoder wrote, of the given length in bits, with
			// start codes and emulation prevention bytes.
			bool AddPackedHeader(VAEncPackedHeaderType type, const std::vector<uint8_t> & data, size_t bits);
			// The rate control, frame rate and HRD parameters, for constant
			// bitrate with the given initial quantizer.
			bool AddRateControl(uint32_t bitrateBps, uint32_t framerate, uint32_t initialQp);

			// Encodes the input surface with the parameter buffers added, and
			// waits for it.
			bool Encode();
			bool ReadOutput(std::vector<uint8_t> & output);
			// Drops the parameter buffers of the frame.
			void DropBuffers();

			bool Check(VAStatus status, const char * operation) const;

		private:
			VaapiLibrary & library;
			const VaapiFunctions & va;
			const std::string name;

			VAConfigID config;
			VAContextID context;
			VASurfaceID surfaces[3];
			VABufferID codedBuffer;
			std::vector<VABufferID> frameBuffers;
	};
}

#endif
