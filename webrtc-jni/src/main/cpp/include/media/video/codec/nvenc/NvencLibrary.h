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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_NVENC_LIBRARY_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_NVENC_LIBRARY_H_

#include "media/video/codec/nvenc/DynamicLibrary.h"

#include <nvEncodeAPI.h>

#include <string>

#ifdef _WIN32
#define JNI_CUDAAPI __stdcall
#else
#define JNI_CUDAAPI
#endif

namespace jni
{
	// The few types of the CUDA driver API that NVENC needs, declared here so
	// that building does not need the CUDA toolkit.
	using CUresult = int;
	using CUdevice = int;
	using CUcontext = struct CUctx_st *;

	constexpr CUresult kCudaSuccess = 0;

	// The CUDA driver and the NVENC library of the NVIDIA driver, loaded
	// once, when first asked for, and kept for the life of the process.
	// NVENC sessions run on the primary CUDA context of the first device.
	class NvencLibrary
	{
		public:
			// Returns the library, or null if there is no NVIDIA driver, no
			// CUDA device, or a driver too old for the NVENC API version this
			// library is built against.
			static NvencLibrary * Get();

			NvencLibrary(const NvencLibrary &) = delete;
			NvencLibrary & operator=(const NvencLibrary &) = delete;

			const NV_ENCODE_API_FUNCTION_LIST & Api() const;

			CUdevice Device() const;

			// The name of the device, e.g. "NVIDIA GeForce RTX 4070".
			const std::string & DeviceName() const;

			// Retains the primary context of the device. Every successful call
			// has to be matched by ReleaseContext().
			bool RetainContext(CUcontext * context) const;
			void ReleaseContext() const;

			bool PushContext(CUcontext context) const;
			void PopContext() const;

		private:
			NvencLibrary() = default;

			bool Load();

		private:
			using CuInit = CUresult (JNI_CUDAAPI *)(unsigned int flags);
			using CuDeviceGetCount = CUresult (JNI_CUDAAPI *)(int * count);
			using CuDeviceGet = CUresult (JNI_CUDAAPI *)(CUdevice * device, int ordinal);
			using CuDeviceGetName = CUresult (JNI_CUDAAPI *)(char * name, int length, CUdevice device);
			using CuDevicePrimaryCtxRetain = CUresult (JNI_CUDAAPI *)(CUcontext * context, CUdevice device);
			using CuDevicePrimaryCtxRelease = CUresult (JNI_CUDAAPI *)(CUdevice device);
			using CuCtxPushCurrent = CUresult (JNI_CUDAAPI *)(CUcontext context);
			using CuCtxPopCurrent = CUresult (JNI_CUDAAPI *)(CUcontext * context);

			using NvEncodeApiGetMaxSupportedVersion = NVENCSTATUS (NVENCAPI *)(uint32_t * version);
			using NvEncodeApiCreateInstance = NVENCSTATUS (NVENCAPI *)(NV_ENCODE_API_FUNCTION_LIST * functions);

			DynamicLibrary cuda;
			DynamicLibrary nvenc;

			CuInit cuInit = nullptr;
			CuDeviceGetCount cuDeviceGetCount = nullptr;
			CuDeviceGet cuDeviceGet = nullptr;
			CuDeviceGetName cuDeviceGetName = nullptr;
			CuDevicePrimaryCtxRetain cuDevicePrimaryCtxRetain = nullptr;
			CuDevicePrimaryCtxRelease cuDevicePrimaryCtxRelease = nullptr;
			CuCtxPushCurrent cuCtxPushCurrent = nullptr;
			CuCtxPopCurrent cuCtxPopCurrent = nullptr;

			NV_ENCODE_API_FUNCTION_LIST api = {};
			CUdevice device = 0;
			std::string deviceName;
	};
}

#endif
