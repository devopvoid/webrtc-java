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

#include "media/video/codec/nvenc/NvencLibrary.h"

#include "rtc_base/logging.h"

#include <cstring>
#include <mutex>
#include <vector>

namespace jni
{
	namespace
	{
#ifdef _WIN32
		constexpr const char * kCudaLibrary = "nvcuda.dll";
		constexpr const char * kNvencLibrary = "nvEncodeAPI64.dll";
#else
		constexpr const char * kCudaLibrary = "libcuda.so.1";
		constexpr const char * kNvencLibrary = "libnvidia-encode.so.1";
#endif

		// The API version NvEncodeAPIGetMaxSupportedVersion() reports is the
		// major version shifted by 4, plus the minor version.
		constexpr uint32_t kRequiredApiVersion = (NVENCAPI_MAJOR_VERSION << 4) | NVENCAPI_MINOR_VERSION;
	}

	NvencLibrary * NvencLibrary::Get()
	{
		static std::once_flag once;
		static NvencLibrary * instance = nullptr;

		std::call_once(once, [] {
			auto library = new NvencLibrary();

			if (library->Load()) {
				instance = library;
			}
			else {
				delete library;
			}
		});

		return instance;
	}

	bool NvencLibrary::Load()
	{
		if (!cuda.Open(kCudaLibrary) || !nvenc.Open(kNvencLibrary)) {
			RTC_LOG(LS_INFO) << "NVENC: no NVIDIA driver";
			return false;
		}

		// Versioned symbols first, as the CUDA headers map the names to them.
		bool resolved = cuda.Resolve("cuInit", cuInit)
			&& cuda.Resolve("cuDeviceGetCount", cuDeviceGetCount)
			&& cuda.Resolve("cuDeviceGet", cuDeviceGet)
			&& cuda.Resolve("cuDeviceGetName", cuDeviceGetName)
			&& cuda.Resolve("cuDevicePrimaryCtxRetain", cuDevicePrimaryCtxRetain)
			&& (cuda.Resolve("cuDevicePrimaryCtxRelease_v2", cuDevicePrimaryCtxRelease)
				|| cuda.Resolve("cuDevicePrimaryCtxRelease", cuDevicePrimaryCtxRelease))
			&& cuda.Resolve("cuCtxPushCurrent_v2", cuCtxPushCurrent)
			&& cuda.Resolve("cuCtxPopCurrent_v2", cuCtxPopCurrent);

		NvEncodeApiGetMaxSupportedVersion getMaxSupportedVersion = nullptr;
		NvEncodeApiCreateInstance createInstance = nullptr;

		resolved = resolved
			&& nvenc.Resolve("NvEncodeAPIGetMaxSupportedVersion", getMaxSupportedVersion)
			&& nvenc.Resolve("NvEncodeAPICreateInstance", createInstance);

		if (!resolved) {
			RTC_LOG(LS_WARNING) << "NVENC: the driver lacks functions this library needs";
			return false;
		}

		uint32_t version = 0;

		if (getMaxSupportedVersion(&version) != NV_ENC_SUCCESS || version < kRequiredApiVersion) {
			RTC_LOG(LS_WARNING) << "NVENC: the driver supports API " << (version >> 4) << "." << (version & 0xF)
				<< ", " << NVENCAPI_MAJOR_VERSION << "." << NVENCAPI_MINOR_VERSION << " is needed; update the driver";
			return false;
		}

		api.version = NV_ENCODE_API_FUNCTION_LIST_VER;

		if (createInstance(&api) != NV_ENC_SUCCESS) {
			RTC_LOG(LS_WARNING) << "NVENC: the driver failed to provide the API";
			return false;
		}

		int count = 0;

		if (cuInit(0) != kCudaSuccess || cuDeviceGetCount(&count) != kCudaSuccess || count == 0 ||
			cuDeviceGet(&device, 0) != kCudaSuccess)
		{
			RTC_LOG(LS_INFO) << "NVENC: no CUDA device";
			return false;
		}

		char name[256] = {};

		if (cuDeviceGetName(name, sizeof(name) - 1, device) == kCudaSuccess) {
			deviceName = name;
		}

		QueryCodecs();

		if (!h264 && !av1) {
			RTC_LOG(LS_INFO) << "NVENC: " << deviceName << " encodes neither H.264 nor AV1";
			return false;
		}

		RTC_LOG(LS_INFO) << "NVENC available on " << deviceName << ", H.264: " << h264 << ", AV1: " << av1;

		return true;
	}

	void NvencLibrary::QueryCodecs()
	{
		CUcontext context = nullptr;

		if (!RetainContext(&context)) {
			return;
		}

		if (PushContext(context)) {
			NV_ENC_OPEN_ENCODE_SESSION_EX_PARAMS params = {};
			params.version = NV_ENC_OPEN_ENCODE_SESSION_EX_PARAMS_VER;
			params.deviceType = NV_ENC_DEVICE_TYPE_CUDA;
			params.device = context;
			params.apiVersion = NVENCAPI_VERSION;

			void * encoder = nullptr;

			if (api.nvEncOpenEncodeSessionEx(&params, &encoder) == NV_ENC_SUCCESS) {
				uint32_t count = 0;

				if (api.nvEncGetEncodeGUIDCount(encoder, &count) == NV_ENC_SUCCESS && count > 0) {
					std::vector<GUID> guids(count);
					uint32_t found = 0;

					if (api.nvEncGetEncodeGUIDs(encoder, guids.data(), count, &found) == NV_ENC_SUCCESS) {
						for (uint32_t i = 0; i < found; i++) {
							h264 |= std::memcmp(&guids[i], &NV_ENC_CODEC_H264_GUID, sizeof(GUID)) == 0;
							av1 |= std::memcmp(&guids[i], &NV_ENC_CODEC_AV1_GUID, sizeof(GUID)) == 0;
						}
					}
				}

				api.nvEncDestroyEncoder(encoder);
			}
			else {
				// No session left to ask right now; every NVENC GPU encodes
				// H.264.
				h264 = true;
			}

			PopContext();
		}

		ReleaseContext();
	}

	bool NvencLibrary::SupportsH264() const
	{
		return h264;
	}

	bool NvencLibrary::SupportsAv1() const
	{
		return av1;
	}

	const NV_ENCODE_API_FUNCTION_LIST & NvencLibrary::Api() const
	{
		return api;
	}

	CUdevice NvencLibrary::Device() const
	{
		return device;
	}

	const std::string & NvencLibrary::DeviceName() const
	{
		return deviceName;
	}

	bool NvencLibrary::RetainContext(CUcontext * context) const
	{
		return cuDevicePrimaryCtxRetain(context, device) == kCudaSuccess;
	}

	void NvencLibrary::ReleaseContext() const
	{
		cuDevicePrimaryCtxRelease(device);
	}

	bool NvencLibrary::PushContext(CUcontext context) const
	{
		return cuCtxPushCurrent(context) == kCudaSuccess;
	}

	void NvencLibrary::PopContext() const
	{
		CUcontext context = nullptr;

		cuCtxPopCurrent(&context);
	}
}
