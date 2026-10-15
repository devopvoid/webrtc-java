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

#include "media/video/codec/linux/VaapiDecodeSession.h"

#include "rtc_base/logging.h"
#include "third_party/libyuv/include/libyuv/convert.h"

#include <utility>

namespace jni
{
	VaapiDecodeSession::VaapiDecodeSession(VaapiLibrary & library, std::string name) :
		library(library),
		va(library.Api()),
		name(std::move(name)),
		config(VA_INVALID_ID),
		context(VA_INVALID_ID),
		width(0),
		height(0),
		downloadImage()
	{
		downloadImage.image_id = VA_INVALID_ID;
	}

	VaapiDecodeSession::~VaapiDecodeSession()
	{
		Destroy();
	}

	bool VaapiDecodeSession::Check(VAStatus status, const char * operation) const
	{
		if (status == VA_STATUS_SUCCESS) {
			return true;
		}

		RTC_LOG(LS_WARNING) << name << ": " << operation << " failed: " << library.ErrorString(status);

		return false;
	}

	bool VaapiDecodeSession::Create(VAProfile profile, uint32_t pictureWidth, uint32_t pictureHeight,
		uint32_t surfaceCount)
	{
		Destroy();

		VADisplay display = library.Display();

		VAConfigAttrib attribute = {};
		attribute.type = VAConfigAttribRTFormat;
		attribute.value = VA_RT_FORMAT_YUV420;

		if (!Check(va.CreateConfig(display, profile, VAEntrypointVLD, &attribute, 1, &config),
			"creating the configuration"))
		{
			config = VA_INVALID_ID;
			return false;
		}

		surfaces.assign(surfaceCount, VA_INVALID_SURFACE);

		if (!Check(va.CreateSurfaces(display, VA_RT_FORMAT_YUV420, pictureWidth, pictureHeight, surfaces.data(),
			surfaceCount, nullptr, 0), "creating surfaces"))
		{
			surfaces.clear();
			Destroy();
			return false;
		}

		if (!Check(va.CreateContext(display, config, static_cast<int>(pictureWidth), static_cast<int>(pictureHeight),
			VA_PROGRESSIVE, surfaces.data(), static_cast<int>(surfaceCount), &context), "creating the context"))
		{
			context = VA_INVALID_ID;
			Destroy();
			return false;
		}

		width = pictureWidth;
		height = pictureHeight;

		return true;
	}

	void VaapiDecodeSession::Destroy()
	{
		VADisplay display = library.Display();

		if (downloadImage.image_id != VA_INVALID_ID) {
			va.DestroyImage(display, downloadImage.image_id);
			downloadImage.image_id = VA_INVALID_ID;
		}
		if (context != VA_INVALID_ID) {
			va.DestroyContext(display, context);
			context = VA_INVALID_ID;
		}
		if (!surfaces.empty()) {
			va.DestroySurfaces(display, surfaces.data(), static_cast<int>(surfaces.size()));
			surfaces.clear();
		}
		if (config != VA_INVALID_ID) {
			va.DestroyConfig(display, config);
			config = VA_INVALID_ID;
		}

		width = 0;
		height = 0;
	}

	bool VaapiDecodeSession::IsCreated() const
	{
		return context != VA_INVALID_ID;
	}

	uint32_t VaapiDecodeSession::Width() const
	{
		return width;
	}

	uint32_t VaapiDecodeSession::Height() const
	{
		return height;
	}

	int VaapiDecodeSession::SurfaceCount() const
	{
		return static_cast<int>(surfaces.size());
	}

	VASurfaceID VaapiDecodeSession::Surface(int index) const
	{
		return surfaces[index];
	}

	bool VaapiDecodeSession::Decode(int surface, const std::vector<Buffer> & buffers)
	{
		VADisplay display = library.Display();
		std::vector<VABufferID> created;
		bool ok = true;

		for (const Buffer & buffer : buffers) {
			VABufferID id = VA_INVALID_ID;

			if (!Check(va.CreateBuffer(display, context, buffer.type, static_cast<unsigned int>(buffer.size), 1,
				const_cast<void *>(buffer.data), &id), "creating a buffer"))
			{
				ok = false;
				break;
			}

			created.push_back(id);
		}

		bool decoded = false;

		if (ok && Check(va.BeginPicture(display, context, surfaces[surface]), "beginning a picture")) {
			const bool rendered = Check(va.RenderPicture(display, context, created.data(),
				static_cast<int>(created.size())), "rendering a picture");
			// A picture that was begun has to be ended.
			const bool ended = Check(va.EndPicture(display, context), "ending a picture");

			decoded = rendered && ended && Check(va.SyncSurface(display, surfaces[surface]), "waiting for a picture");
		}

		for (VABufferID id : created) {
			va.DestroyBuffer(display, id);
		}

		return decoded;
	}

	webrtc::scoped_refptr<webrtc::I420Buffer> VaapiDecodeSession::Download(int surface, uint32_t x, uint32_t y,
		uint32_t regionWidth, uint32_t regionHeight)
	{
		VADisplay display = library.Display();

		// Copying a surface into an image reads it from cached memory, which
		// mapping it directly does not. Where the driver does not copy, the
		// surface is mapped.
		if (downloadImage.image_id != VA_INVALID_ID
			&& (downloadImage.width != regionWidth || downloadImage.height != regionHeight))
		{
			va.DestroyImage(display, downloadImage.image_id);
			downloadImage.image_id = VA_INVALID_ID;
		}

		if (downloadImage.image_id == VA_INVALID_ID) {
			VAImageFormat format = {};
			format.fourcc = VA_FOURCC_NV12;
			format.byte_order = VA_LSB_FIRST;
			format.bits_per_pixel = 12;

			if (va.CreateImage(display, &format, static_cast<int>(regionWidth), static_cast<int>(regionHeight),
				&downloadImage) != VA_STATUS_SUCCESS)
			{
				downloadImage.image_id = VA_INVALID_ID;
			}
		}

		VAImage mapped = {};
		mapped.image_id = VA_INVALID_ID;
		bool derived = false;
		size_t offsetY = 0;
		size_t offsetUv = 0;

		if (downloadImage.image_id != VA_INVALID_ID
			&& va.GetImage(display, surfaces[surface], static_cast<int>(x), static_cast<int>(y), regionWidth,
				regionHeight, downloadImage.image_id) == VA_STATUS_SUCCESS)
		{
			mapped = downloadImage;
		}
		else if (va.DeriveImage(display, surfaces[surface], &mapped) == VA_STATUS_SUCCESS) {
			derived = true;

			if (mapped.format.fourcc != VA_FOURCC_NV12) {
				RTC_LOG(LS_WARNING) << name << ": the decoded surface is not NV12";
				va.DestroyImage(display, mapped.image_id);
				return nullptr;
			}

			// The region within the whole surface.
			offsetY = y * mapped.pitches[0] + x;
			offsetUv = (y / 2) * mapped.pitches[1] + x;
		}
		else {
			RTC_LOG(LS_WARNING) << name << ": the decoded surface cannot be read";
			return nullptr;
		}

		void * data = nullptr;
		bool converted = false;
		webrtc::scoped_refptr<webrtc::I420Buffer> i420 = webrtc::I420Buffer::Create(static_cast<int>(regionWidth),
			static_cast<int>(regionHeight));

		if (Check(va.MapBuffer(display, mapped.buf, &data), "mapping a decoded picture")) {
			const uint8_t * base = static_cast<const uint8_t *>(data);

			converted = libyuv::NV12ToI420(base + mapped.offsets[0] + offsetY, static_cast<int>(mapped.pitches[0]),
				base + mapped.offsets[1] + offsetUv, static_cast<int>(mapped.pitches[1]),
				i420->MutableDataY(), i420->StrideY(), i420->MutableDataU(), i420->StrideU(),
				i420->MutableDataV(), i420->StrideV(), static_cast<int>(regionWidth),
				static_cast<int>(regionHeight)) == 0;

			va.UnmapBuffer(display, mapped.buf);
		}

		if (derived) {
			va.DestroyImage(display, mapped.image_id);
		}

		return converted ? i420 : nullptr;
	}
}
