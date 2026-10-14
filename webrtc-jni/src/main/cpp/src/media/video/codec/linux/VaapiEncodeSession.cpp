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

#include "media/video/codec/linux/VaapiEncodeSession.h"

#include "api/video/i420_buffer.h"
#include "rtc_base/logging.h"
#include "third_party/libyuv/include/libyuv/convert_from.h"

#include <cstring>

namespace jni
{
	namespace
	{
		// How many seconds of video the rate control may buffer, as a
		// multiple of the bitrate.
		constexpr uint32_t kHrdBufferNumerator = 3;
		constexpr uint32_t kHrdBufferDenominator = 2;
	}

	VaapiEncodeSession::VaapiEncodeSession(VaapiLibrary & library, std::string name) :
		library(library),
		va(library.Api()),
		name(std::move(name)),
		config(VA_INVALID_ID),
		context(VA_INVALID_ID),
		surfaces{ VA_INVALID_SURFACE, VA_INVALID_SURFACE, VA_INVALID_SURFACE },
		codedBuffer(VA_INVALID_ID)
	{
	}

	VaapiEncodeSession::~VaapiEncodeSession()
	{
		Destroy();
	}

	bool VaapiEncodeSession::Check(VAStatus status, const char * operation) const
	{
		if (status == VA_STATUS_SUCCESS) {
			return true;
		}

		RTC_LOG(LS_WARNING) << name << ": " << operation << " failed: " << library.ErrorString(status);

		return false;
	}

	bool VaapiEncodeSession::Create(VAProfile profile, VAEntrypoint entrypoint, uint32_t pictureWidth,
		uint32_t pictureHeight, uint32_t surfaceWidth, uint32_t surfaceHeight, uint32_t packedHeaders)
	{
		Destroy();

		VADisplay display = library.Display();

		VAConfigAttrib attributes[3] = {};
		attributes[0].type = VAConfigAttribRTFormat;
		attributes[0].value = VA_RT_FORMAT_YUV420;
		attributes[1].type = VAConfigAttribRateControl;
		attributes[1].value = VA_RC_CBR;
		attributes[2].type = VAConfigAttribEncPackedHeaders;
		attributes[2].value = packedHeaders;

		const int attributeCount = packedHeaders != VA_ENC_PACKED_HEADER_NONE ? 3 : 2;

		if (!Check(va.CreateConfig(display, profile, entrypoint, attributes, attributeCount, &config),
			"creating the configuration"))
		{
			config = VA_INVALID_ID;
			return false;
		}

		if (!Check(va.CreateSurfaces(display, VA_RT_FORMAT_YUV420, surfaceWidth, surfaceHeight, surfaces, 3, nullptr, 0),
			"creating surfaces"))
		{
			for (VASurfaceID & surface : surfaces) {
				surface = VA_INVALID_SURFACE;
			}

			return false;
		}

		if (!Check(va.CreateContext(display, config, pictureWidth, pictureHeight, VA_PROGRESSIVE, surfaces, 3, &context),
			"creating the context"))
		{
			context = VA_INVALID_ID;
			return false;
		}

		// Room for a frame that does not compress at all.
		const unsigned int codedSize = surfaceWidth * surfaceHeight * 3 / 2 + 64 * 1024;

		if (!Check(va.CreateBuffer(display, context, VAEncCodedBufferType, codedSize, 1, nullptr, &codedBuffer),
			"creating the output buffer"))
		{
			codedBuffer = VA_INVALID_ID;
			return false;
		}

		return true;
	}

	void VaapiEncodeSession::Destroy()
	{
		VADisplay display = library.Display();

		DropBuffers();

		if (codedBuffer != VA_INVALID_ID) {
			va.DestroyBuffer(display, codedBuffer);
			codedBuffer = VA_INVALID_ID;
		}
		if (context != VA_INVALID_ID) {
			va.DestroyContext(display, context);
			context = VA_INVALID_ID;
		}
		if (surfaces[0] != VA_INVALID_SURFACE) {
			va.DestroySurfaces(display, surfaces, 3);
		}

		for (VASurfaceID & surface : surfaces) {
			surface = VA_INVALID_SURFACE;
		}

		if (config != VA_INVALID_ID) {
			va.DestroyConfig(display, config);
			config = VA_INVALID_ID;
		}
	}

	VASurfaceID VaapiEncodeSession::InputSurface() const
	{
		return surfaces[0];
	}

	VASurfaceID VaapiEncodeSession::Reconstructed(int index) const
	{
		return surfaces[1 + index];
	}

	VABufferID VaapiEncodeSession::CodedBuffer() const
	{
		return codedBuffer;
	}

	bool VaapiEncodeSession::Upload(const webrtc::VideoFrame & frame, int width, int height, int tolerance)
	{
		webrtc::scoped_refptr<webrtc::I420BufferInterface> i420 = frame.video_frame_buffer()->ToI420();

		if (!i420) {
			return false;
		}

		const bool cropped = i420->width() >= width && i420->width() - width <= tolerance
			&& i420->height() >= height && i420->height() - height <= tolerance;

		if (!cropped) {
			webrtc::scoped_refptr<webrtc::I420Buffer> scaled = webrtc::I420Buffer::Create(width, height);
			scaled->ScaleFrom(*i420);
			i420 = scaled;
		}

		VADisplay display = library.Display();
		VAImage image = {};
		image.image_id = VA_INVALID_ID;

		// Writing into the surface directly where the driver allows it,
		// otherwise into an image that is copied into the surface.
		bool derived = va.DeriveImage(display, InputSurface(), &image) == VA_STATUS_SUCCESS
			&& image.format.fourcc == VA_FOURCC_NV12;

		if (!derived) {
			if (image.image_id != VA_INVALID_ID) {
				va.DestroyImage(display, image.image_id);
			}

			VAImageFormat format = {};
			format.fourcc = VA_FOURCC_NV12;
			format.byte_order = VA_LSB_FIRST;
			format.bits_per_pixel = 12;

			if (!Check(va.CreateImage(display, &format, width, height, &image), "creating an image")) {
				return false;
			}
		}

		void * data = nullptr;
		bool uploaded = Check(va.MapBuffer(display, image.buf, &data), "mapping an image");

		if (uploaded) {
			uint8_t * base = static_cast<uint8_t *>(data);

			libyuv::I420ToNV12(i420->DataY(), i420->StrideY(), i420->DataU(), i420->StrideU(),
				i420->DataV(), i420->StrideV(),
				base + image.offsets[0], static_cast<int>(image.pitches[0]),
				base + image.offsets[1], static_cast<int>(image.pitches[1]),
				width, height);

			va.UnmapBuffer(display, image.buf);

			if (!derived) {
				uploaded = Check(va.PutImage(display, InputSurface(), image.image_id, 0, 0, width, height,
					0, 0, width, height), "copying an image");
			}
		}

		va.DestroyImage(display, image.image_id);

		return uploaded;
	}

	bool VaapiEncodeSession::AddBuffer(VABufferType type, void * data, size_t size)
	{
		VABufferID buffer = VA_INVALID_ID;

		if (!Check(va.CreateBuffer(library.Display(), context, type, static_cast<unsigned int>(size), 1, data,
			&buffer), "creating a parameter buffer"))
		{
			return false;
		}

		frameBuffers.push_back(buffer);

		return true;
	}

	bool VaapiEncodeSession::AddMiscParameter(VAEncMiscParameterType type, const void * data, size_t size)
	{
		// A VAEncMiscParameterBuffer: the type, followed by the parameter.
		std::vector<uint8_t> buffer(sizeof(VAEncMiscParameterBuffer) + size);

		auto * header = reinterpret_cast<VAEncMiscParameterBuffer *>(buffer.data());
		header->type = type;
		std::memcpy(header->data, data, size);

		return AddBuffer(VAEncMiscParameterBufferType, buffer.data(), buffer.size());
	}

	bool VaapiEncodeSession::AddPackedHeader(VAEncPackedHeaderType type, const std::vector<uint8_t> & data,
		size_t bits)
	{
		VAEncPackedHeaderParameterBuffer parameters = {};
		parameters.type = type;
		parameters.bit_length = static_cast<uint32_t>(bits);
		parameters.has_emulation_bytes = 1;

		return AddBuffer(VAEncPackedHeaderParameterBufferType, &parameters, sizeof(parameters))
			&& AddBuffer(VAEncPackedHeaderDataBufferType, const_cast<uint8_t *>(data.data()), data.size());
	}

	bool VaapiEncodeSession::AddRateControl(uint32_t bitrateBps, uint32_t framerate, uint32_t initialQp)
	{
		VAEncMiscParameterRateControl rateControl = {};
		rateControl.bits_per_second = bitrateBps;
		rateControl.target_percentage = 100;
		rateControl.window_size = 1000;
		rateControl.initial_qp = initialQp;

		VAEncMiscParameterFrameRate frameRate = {};
		// The numerator in the low 16 bits, the denominator in the high.
		frameRate.framerate = framerate | (1u << 16);

		VAEncMiscParameterHRD hrd = {};
		hrd.buffer_size = static_cast<uint32_t>(
			static_cast<uint64_t>(bitrateBps) * kHrdBufferNumerator / kHrdBufferDenominator);
		hrd.initial_buffer_fullness = hrd.buffer_size / 2;

		return AddMiscParameter(VAEncMiscParameterTypeRateControl, &rateControl, sizeof(rateControl))
			&& AddMiscParameter(VAEncMiscParameterTypeFrameRate, &frameRate, sizeof(frameRate))
			&& AddMiscParameter(VAEncMiscParameterTypeHRD, &hrd, sizeof(hrd));
	}

	bool VaapiEncodeSession::Encode()
	{
		VADisplay display = library.Display();

		if (!Check(va.BeginPicture(display, context, InputSurface()), "beginning a picture")) {
			return false;
		}

		bool rendered = Check(va.RenderPicture(display, context, frameBuffers.data(),
			static_cast<int>(frameBuffers.size())), "rendering a picture");

		// A picture that was begun has to be ended.
		bool ended = Check(va.EndPicture(display, context), "ending a picture");

		return rendered && ended && Check(va.SyncSurface(display, InputSurface()), "waiting for a picture");
	}

	bool VaapiEncodeSession::ReadOutput(std::vector<uint8_t> & output)
	{
		VADisplay display = library.Display();
		void * data = nullptr;

		if (!Check(va.MapBuffer(display, codedBuffer, &data), "mapping the output")) {
			return false;
		}

		bool complete = true;

		for (auto * segment = static_cast<VACodedBufferSegment *>(data); segment != nullptr;
			segment = static_cast<VACodedBufferSegment *>(segment->next))
		{
			if (segment->status & VA_CODED_BUF_STATUS_SLICE_OVERFLOW_MASK) {
				complete = false;
			}

			const uint8_t * bytes = static_cast<const uint8_t *>(segment->buf);
			output.insert(output.end(), bytes, bytes + segment->size);
		}

		va.UnmapBuffer(display, codedBuffer);

		if (!complete || output.empty()) {
			RTC_LOG(LS_WARNING) << name << ": a frame did not fit the output buffer";
			return false;
		}

		return true;
	}

	void VaapiEncodeSession::DropBuffers()
	{
		for (VABufferID buffer : frameBuffers) {
			va.DestroyBuffer(library.Display(), buffer);
		}

		frameBuffers.clear();
	}
}
