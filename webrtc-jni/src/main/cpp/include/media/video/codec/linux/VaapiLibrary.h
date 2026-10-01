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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_VAAPI_LIBRARY_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_VAAPI_LIBRARY_H_

#include "media/video/codec/nvenc/DynamicLibrary.h"

#include <va/va.h>
#include <va/va_drm.h>

#include <string>

namespace jni
{
	// The functions of libva this library uses, typed after the libva
	// headers, which are only declared, never linked.
	struct VaapiFunctions
	{
		decltype(&::vaGetDisplayDRM) GetDisplayDRM = nullptr;
		decltype(&::vaInitialize) Initialize = nullptr;
		decltype(&::vaTerminate) Terminate = nullptr;
		decltype(&::vaSetInfoCallback) SetInfoCallback = nullptr;
		decltype(&::vaQueryVendorString) QueryVendorString = nullptr;
		decltype(&::vaErrorStr) ErrorStr = nullptr;
		decltype(&::vaMaxNumEntrypoints) MaxNumEntrypoints = nullptr;
		decltype(&::vaQueryConfigEntrypoints) QueryConfigEntrypoints = nullptr;
		decltype(&::vaGetConfigAttributes) GetConfigAttributes = nullptr;
		decltype(&::vaCreateConfig) CreateConfig = nullptr;
		decltype(&::vaDestroyConfig) DestroyConfig = nullptr;
		decltype(&::vaCreateSurfaces) CreateSurfaces = nullptr;
		decltype(&::vaDestroySurfaces) DestroySurfaces = nullptr;
		decltype(&::vaCreateContext) CreateContext = nullptr;
		decltype(&::vaDestroyContext) DestroyContext = nullptr;
		decltype(&::vaCreateBuffer) CreateBuffer = nullptr;
		decltype(&::vaDestroyBuffer) DestroyBuffer = nullptr;
		decltype(&::vaMapBuffer) MapBuffer = nullptr;
		decltype(&::vaUnmapBuffer) UnmapBuffer = nullptr;
		decltype(&::vaBeginPicture) BeginPicture = nullptr;
		decltype(&::vaRenderPicture) RenderPicture = nullptr;
		decltype(&::vaEndPicture) EndPicture = nullptr;
		decltype(&::vaSyncSurface) SyncSurface = nullptr;
		decltype(&::vaDeriveImage) DeriveImage = nullptr;
		decltype(&::vaCreateImage) CreateImage = nullptr;
		decltype(&::vaPutImage) PutImage = nullptr;
		decltype(&::vaDestroyImage) DestroyImage = nullptr;
	};

	// libva, loaded once, when first asked for, with the display of the
	// first GPU whose driver encodes H.264 Constrained Baseline with constant
	// bitrate. Kept for the life of the process; the display is shared by all
	// encoders, which libva allows.
	class VaapiLibrary
	{
		public:
			// Returns the library, or null if libva is not installed, or no GPU
			// driver encodes H.264 through it.
			static VaapiLibrary * Get();

			VaapiLibrary(const VaapiLibrary &) = delete;
			VaapiLibrary & operator=(const VaapiLibrary &) = delete;

			const VaapiFunctions & Api() const;

			VADisplay Display() const;

			// The encode entrypoint the driver offers, full or low-power.
			VAEntrypoint Entrypoint() const;

			// The driver, e.g. "Intel iHD driver for Intel(R) Gen Graphics".
			const std::string & Vendor() const;

			const char * ErrorString(VAStatus status) const;

		private:
			VaapiLibrary() = default;

			bool Load();
			bool OpenDisplay();
			bool SupportsEncoding(VADisplay display, VAEntrypoint * entrypoint) const;

		private:
			DynamicLibrary va;
			DynamicLibrary vaDrm;
			VaapiFunctions api;

			int fd = -1;
			VADisplay display = nullptr;
			VAEntrypoint entrypoint = VAEntrypointEncSlice;
			std::string vendor;
	};
}

#endif
