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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_NVDEC_LIBRARY_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_NVDEC_LIBRARY_H_

#include "media/video/codec/nvenc/DynamicLibrary.h"

#include <ffnvcodec/dynlink_cuda.h>
#include <ffnvcodec/dynlink_cuviddec.h>
#include <ffnvcodec/dynlink_nvcuvid.h>

#include <string>

namespace jni
{
	// The functions of the CUDA driver and of libnvcuvid that decoding uses.
	struct NvdecApi
	{
		tcuInit * cuInit = nullptr;
		tcuDeviceGetCount * cuDeviceGetCount = nullptr;
		tcuDeviceGet * cuDeviceGet = nullptr;
		tcuDeviceGetName * cuDeviceGetName = nullptr;
		tcuDevicePrimaryCtxRetain * cuDevicePrimaryCtxRetain = nullptr;
		tcuDevicePrimaryCtxRelease * cuDevicePrimaryCtxRelease = nullptr;
		tcuCtxPushCurrent_v2 * cuCtxPushCurrent = nullptr;
		tcuCtxPopCurrent_v2 * cuCtxPopCurrent = nullptr;
		tcuMemcpy2D_v2 * cuMemcpy2D = nullptr;

		tcuvidGetDecoderCaps * cuvidGetDecoderCaps = nullptr;
		tcuvidCreateDecoder * cuvidCreateDecoder = nullptr;
		tcuvidDestroyDecoder * cuvidDestroyDecoder = nullptr;
		tcuvidDecodePicture * cuvidDecodePicture = nullptr;
		tcuvidMapVideoFrame64 * cuvidMapVideoFrame = nullptr;
		tcuvidUnmapVideoFrame64 * cuvidUnmapVideoFrame = nullptr;
		tcuvidCreateVideoParser * cuvidCreateVideoParser = nullptr;
		tcuvidParseVideoData * cuvidParseVideoData = nullptr;
		tcuvidDestroyVideoParser * cuvidDestroyVideoParser = nullptr;
	};

	// The CUDA driver and the NVDEC library of the NVIDIA driver, loaded once,
	// when first asked for, and kept for the life of the process. Decoders run
	// on the primary CUDA context of the first device.
	class NvdecLibrary
	{
		public:
			// Returns the library, or null if there is no NVIDIA driver, no
			// CUDA device, or a device that decodes neither H.264 nor VP9.
			static NvdecLibrary * Get();

			NvdecLibrary(const NvdecLibrary &) = delete;
			NvdecLibrary & operator=(const NvdecLibrary &) = delete;

			const NvdecApi & Api() const;

			// The name of the device, e.g. "NVIDIA GeForce RTX 4070".
			const std::string & DeviceName() const;

			bool SupportsH264() const;
			bool SupportsVp9() const;

			// Whether the device decodes the codec, 8 bit 4:2:0, at this
			// coded size. Needs the context current.
			bool Supports(cudaVideoCodec codec, unsigned int width, unsigned int height) const;

			// Retains the primary context of the device. Every successful call
			// has to be matched by ReleaseContext().
			bool RetainContext(CUcontext * context) const;
			void ReleaseContext() const;

			bool PushContext(CUcontext context) const;
			void PopContext() const;

		private:
			NvdecLibrary() = default;

			bool Load();

			// Asks the device whether it decodes the codec at all.
			bool QueryCodec(cudaVideoCodec codec) const;

		private:
			DynamicLibrary cuda;
			DynamicLibrary cuvid;

			NvdecApi api;
			CUdevice device = 0;
			std::string deviceName;
			bool h264 = false;
			bool vp9 = false;
	};

	// Makes a CUDA context current on the calling thread for as long as it
	// lives, which calls into NVDEC need.
	class NvdecContextScope
	{
		public:
			NvdecContextScope(const NvdecLibrary & library, CUcontext context);
			~NvdecContextScope();

			NvdecContextScope(const NvdecContextScope &) = delete;
			NvdecContextScope & operator=(const NvdecContextScope &) = delete;

			bool IsCurrent() const;

		private:
			const NvdecLibrary & library;
			const bool pushed;
	};
}

#endif
