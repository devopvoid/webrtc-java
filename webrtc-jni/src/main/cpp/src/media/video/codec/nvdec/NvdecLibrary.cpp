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

#include "media/video/codec/nvdec/NvdecLibrary.h"

#include "rtc_base/logging.h"

#include <memory>
#include <mutex>

namespace jni
{
	namespace
	{
		constexpr const char * kCudaLibrary = "libcuda.so.1";
		constexpr const char * kCuvidLibrary = "libnvcuvid.so.1";
	}

	NvdecLibrary * NvdecLibrary::Get()
	{
		static std::once_flag once;
		static NvdecLibrary * instance = nullptr;

		std::call_once(once, [] {
			std::unique_ptr<NvdecLibrary> library(new NvdecLibrary());

			if (library->Load()) {
				instance = library.release();
			}
		});

		return instance;
	}

	bool NvdecLibrary::Load()
	{
		if (!cuda.Open(kCudaLibrary) || !cuvid.Open(kCuvidLibrary)) {
			RTC_LOG(LS_INFO) << "NVDEC: no NVIDIA driver";
			return false;
		}

		bool resolved = cuda.Resolve("cuInit", api.cuInit)
			&& cuda.Resolve("cuDeviceGetCount", api.cuDeviceGetCount)
			&& cuda.Resolve("cuDeviceGet", api.cuDeviceGet)
			&& cuda.Resolve("cuDeviceGetName", api.cuDeviceGetName)
			&& cuda.Resolve("cuDevicePrimaryCtxRetain", api.cuDevicePrimaryCtxRetain)
			&& (cuda.Resolve("cuDevicePrimaryCtxRelease_v2", api.cuDevicePrimaryCtxRelease)
				|| cuda.Resolve("cuDevicePrimaryCtxRelease", api.cuDevicePrimaryCtxRelease))
			&& cuda.Resolve("cuCtxPushCurrent_v2", api.cuCtxPushCurrent)
			&& cuda.Resolve("cuCtxPopCurrent_v2", api.cuCtxPopCurrent)
			&& cuda.Resolve("cuMemcpy2D_v2", api.cuMemcpy2D);

		resolved = resolved
			&& cuvid.Resolve("cuvidGetDecoderCaps", api.cuvidGetDecoderCaps)
			&& cuvid.Resolve("cuvidCreateDecoder", api.cuvidCreateDecoder)
			&& cuvid.Resolve("cuvidDestroyDecoder", api.cuvidDestroyDecoder)
			&& cuvid.Resolve("cuvidDecodePicture", api.cuvidDecodePicture)
			&& cuvid.Resolve("cuvidMapVideoFrame64", api.cuvidMapVideoFrame)
			&& cuvid.Resolve("cuvidUnmapVideoFrame64", api.cuvidUnmapVideoFrame)
			&& cuvid.Resolve("cuvidCreateVideoParser", api.cuvidCreateVideoParser)
			&& cuvid.Resolve("cuvidParseVideoData", api.cuvidParseVideoData)
			&& cuvid.Resolve("cuvidDestroyVideoParser", api.cuvidDestroyVideoParser);

		if (!resolved) {
			RTC_LOG(LS_WARNING) << "NVDEC: the driver lacks functions this library needs";
			return false;
		}

		int count = 0;

		if (api.cuInit(0) != CUDA_SUCCESS || api.cuDeviceGetCount(&count) != CUDA_SUCCESS || count == 0 ||
			api.cuDeviceGet(&device, 0) != CUDA_SUCCESS)
		{
			RTC_LOG(LS_INFO) << "NVDEC: no CUDA device";
			return false;
		}

		char name[256] = {};

		if (api.cuDeviceGetName(name, sizeof(name), device) == CUDA_SUCCESS) {
			deviceName = name;
		}

		CUcontext context = nullptr;

		if (!RetainContext(&context)) {
			RTC_LOG(LS_WARNING) << "NVDEC: no CUDA context on " << deviceName;
			return false;
		}

		if (PushContext(context)) {
			h264 = QueryCodec(cudaVideoCodec_H264);
			vp9 = QueryCodec(cudaVideoCodec_VP9);

			PopContext();
		}

		ReleaseContext();

		if (!h264 && !vp9) {
			RTC_LOG(LS_INFO) << "NVDEC: " << deviceName << " decodes neither H.264 nor VP9";
			return false;
		}

		RTC_LOG(LS_INFO) << "NVDEC available on " << deviceName << ", H.264: " << h264 << ", VP9: " << vp9;

		return true;
	}

	bool NvdecLibrary::QueryCodec(cudaVideoCodec codec) const
	{
		CUVIDDECODECAPS caps = {};
		caps.eCodecType = codec;
		caps.eChromaFormat = cudaVideoChromaFormat_420;
		caps.nBitDepthMinus8 = 0;

		return api.cuvidGetDecoderCaps(&caps) == CUDA_SUCCESS && caps.bIsSupported != 0
			&& (caps.nOutputFormatMask & (1 << cudaVideoSurfaceFormat_NV12)) != 0;
	}

	bool NvdecLibrary::Supports(cudaVideoCodec codec, unsigned int width, unsigned int height) const
	{
		CUVIDDECODECAPS caps = {};
		caps.eCodecType = codec;
		caps.eChromaFormat = cudaVideoChromaFormat_420;
		caps.nBitDepthMinus8 = 0;

		if (api.cuvidGetDecoderCaps(&caps) != CUDA_SUCCESS || !caps.bIsSupported) {
			return false;
		}

		return width >= caps.nMinWidth && height >= caps.nMinHeight
			&& width <= caps.nMaxWidth && height <= caps.nMaxHeight
			&& (width / 16) * (height / 16) <= caps.nMaxMBCount;
	}

	const NvdecApi & NvdecLibrary::Api() const
	{
		return api;
	}

	const std::string & NvdecLibrary::DeviceName() const
	{
		return deviceName;
	}

	bool NvdecLibrary::SupportsH264() const
	{
		return h264;
	}

	bool NvdecLibrary::SupportsVp9() const
	{
		return vp9;
	}

	bool NvdecLibrary::RetainContext(CUcontext * context) const
	{
		return api.cuDevicePrimaryCtxRetain(context, device) == CUDA_SUCCESS;
	}

	void NvdecLibrary::ReleaseContext() const
	{
		api.cuDevicePrimaryCtxRelease(device);
	}

	bool NvdecLibrary::PushContext(CUcontext context) const
	{
		return api.cuCtxPushCurrent(context) == CUDA_SUCCESS;
	}

	void NvdecLibrary::PopContext() const
	{
		CUcontext context = nullptr;

		api.cuCtxPopCurrent(&context);
	}

	NvdecContextScope::NvdecContextScope(const NvdecLibrary & library, CUcontext context) :
		library(library),
		pushed(library.PushContext(context))
	{
	}

	NvdecContextScope::~NvdecContextScope()
	{
		if (pushed) {
			library.PopContext();
		}
	}

	bool NvdecContextScope::IsCurrent() const
	{
		return pushed;
	}
}
