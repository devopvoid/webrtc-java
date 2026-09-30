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

#include "media/video/codec/linux/VaapiLibrary.h"

#include "rtc_base/logging.h"

#include <fcntl.h>
#include <unistd.h>

#include <algorithm>
#include <mutex>
#include <vector>

namespace jni
{
	namespace
	{
		// DRM render nodes are numbered from 128, one per GPU.
		constexpr int kFirstRenderNode = 128;
		constexpr int kRenderNodeCount = 8;

		// libva prints what it does to stdout unless told otherwise.
		void IgnoreInfo(void * context, const char * message)
		{
		}
	}

	VaapiLibrary * VaapiLibrary::Get()
	{
		static std::once_flag once;
		static VaapiLibrary * instance = nullptr;

		std::call_once(once, [] {
			auto library = new VaapiLibrary();

			if (library->Load()) {
				instance = library;
			}
			else {
				delete library;
			}
		});

		return instance;
	}

	bool VaapiLibrary::Load()
	{
		if (!va.Open("libva.so.2") || !vaDrm.Open("libva-drm.so.2")) {
			RTC_LOG(LS_INFO) << "VA-API: libva is not installed";
			return false;
		}

		bool resolved = vaDrm.Resolve("vaGetDisplayDRM", api.GetDisplayDRM)
			&& va.Resolve("vaInitialize", api.Initialize)
			&& va.Resolve("vaTerminate", api.Terminate)
			&& va.Resolve("vaQueryVendorString", api.QueryVendorString)
			&& va.Resolve("vaErrorStr", api.ErrorStr)
			&& va.Resolve("vaMaxNumEntrypoints", api.MaxNumEntrypoints)
			&& va.Resolve("vaQueryConfigEntrypoints", api.QueryConfigEntrypoints)
			&& va.Resolve("vaGetConfigAttributes", api.GetConfigAttributes)
			&& va.Resolve("vaCreateConfig", api.CreateConfig)
			&& va.Resolve("vaDestroyConfig", api.DestroyConfig)
			&& va.Resolve("vaCreateSurfaces", api.CreateSurfaces)
			&& va.Resolve("vaDestroySurfaces", api.DestroySurfaces)
			&& va.Resolve("vaCreateContext", api.CreateContext)
			&& va.Resolve("vaDestroyContext", api.DestroyContext)
			&& va.Resolve("vaCreateBuffer", api.CreateBuffer)
			&& va.Resolve("vaDestroyBuffer", api.DestroyBuffer)
			&& va.Resolve("vaMapBuffer", api.MapBuffer)
			&& va.Resolve("vaUnmapBuffer", api.UnmapBuffer)
			&& va.Resolve("vaBeginPicture", api.BeginPicture)
			&& va.Resolve("vaRenderPicture", api.RenderPicture)
			&& va.Resolve("vaEndPicture", api.EndPicture)
			&& va.Resolve("vaSyncSurface", api.SyncSurface)
			&& va.Resolve("vaDeriveImage", api.DeriveImage)
			&& va.Resolve("vaCreateImage", api.CreateImage)
			&& va.Resolve("vaPutImage", api.PutImage)
			&& va.Resolve("vaDestroyImage", api.DestroyImage);

		if (!resolved) {
			RTC_LOG(LS_WARNING) << "VA-API: libva lacks functions this library needs";
			return false;
		}

		// Optional; older versions of libva do not have it.
		va.Resolve("vaSetInfoCallback", api.SetInfoCallback);

		if (!OpenDisplay()) {
			RTC_LOG(LS_INFO) << "VA-API: no GPU driver encodes H.264";
			return false;
		}

		RTC_LOG(LS_INFO) << "VA-API H.264 encoding available: " << vendor;

		return true;
	}

	bool VaapiLibrary::OpenDisplay()
	{
		for (int node = kFirstRenderNode; node < kFirstRenderNode + kRenderNodeCount; node++) {
			const std::string path = "/dev/dri/renderD" + std::to_string(node);
			const int nodeFd = open(path.c_str(), O_RDWR | O_CLOEXEC);

			if (nodeFd < 0) {
				continue;
			}

			VADisplay nodeDisplay = api.GetDisplayDRM(nodeFd);

			if (nodeDisplay != nullptr && api.SetInfoCallback != nullptr) {
				api.SetInfoCallback(nodeDisplay, IgnoreInfo, nullptr);
			}

			int major = 0;
			int minor = 0;

			if (nodeDisplay != nullptr && api.Initialize(nodeDisplay, &major, &minor) == VA_STATUS_SUCCESS) {
				VAEntrypoint nodeEntrypoint;

				if (SupportsEncoding(nodeDisplay, &nodeEntrypoint)) {
					fd = nodeFd;
					display = nodeDisplay;
					entrypoint = nodeEntrypoint;

					const char * vendorString = api.QueryVendorString(display);
					vendor = vendorString != nullptr ? vendorString : "unknown";

					return true;
				}

				api.Terminate(nodeDisplay);
			}

			close(nodeFd);
		}

		return false;
	}

	bool VaapiLibrary::SupportsEncoding(VADisplay nodeDisplay, VAEntrypoint * supported) const
	{
		const int maxEntrypoints = api.MaxNumEntrypoints(nodeDisplay);

		if (maxEntrypoints <= 0) {
			return false;
		}

		std::vector<VAEntrypoint> entrypoints(maxEntrypoints);
		int count = 0;

		if (api.QueryConfigEntrypoints(nodeDisplay, VAProfileH264ConstrainedBaseline, entrypoints.data(),
			&count) != VA_STATUS_SUCCESS)
		{
			return false;
		}

		entrypoints.resize(count);

		// The full encoder where there is one; the low-power one otherwise.
		const VAEntrypoint candidates[] = { VAEntrypointEncSlice, VAEntrypointEncSliceLP };

		for (VAEntrypoint candidate : candidates) {
			if (std::find(entrypoints.begin(), entrypoints.end(), candidate) == entrypoints.end()) {
				continue;
			}

			VAConfigAttrib attributes[2] = {};
			attributes[0].type = VAConfigAttribRTFormat;
			attributes[1].type = VAConfigAttribRateControl;

			if (api.GetConfigAttributes(nodeDisplay, VAProfileH264ConstrainedBaseline, candidate, attributes, 2) !=
				VA_STATUS_SUCCESS)
			{
				continue;
			}

			const bool yuv420 = attributes[0].value != VA_ATTRIB_NOT_SUPPORTED
				&& (attributes[0].value & VA_RT_FORMAT_YUV420);
			const bool cbr = attributes[1].value != VA_ATTRIB_NOT_SUPPORTED
				&& (attributes[1].value & VA_RC_CBR);

			if (yuv420 && cbr) {
				*supported = candidate;
				return true;
			}
		}

		return false;
	}

	const VaapiFunctions & VaapiLibrary::Api() const
	{
		return api;
	}

	VADisplay VaapiLibrary::Display() const
	{
		return display;
	}

	VAEntrypoint VaapiLibrary::Entrypoint() const
	{
		return entrypoint;
	}

	const std::string & VaapiLibrary::Vendor() const
	{
		return vendor;
	}

	const char * VaapiLibrary::ErrorString(VAStatus status) const
	{
		return api.ErrorStr(status);
	}
}
