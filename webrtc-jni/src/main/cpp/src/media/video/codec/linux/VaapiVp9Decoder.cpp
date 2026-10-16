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

#include "media/video/codec/linux/VaapiVp9Decoder.h"

#include "api/video/i420_buffer.h"
#include "api/video/video_frame.h"
#include "modules/video_coding/include/video_error_codes.h"
#include "rtc_base/logging.h"
#include "third_party/libyuv/include/libyuv/convert.h"

#include <algorithm>

namespace jni
{
	namespace
	{
		// The eight reference frames, the frame being decoded, and one more,
		// so that a free surface is always there.
		constexpr int kSurfaceCount = 10;

		// The frame type of an inter frame; a key frame is 0.
		constexpr uint32_t kInterFrame = 1;

		// VA-API takes the interpolation filter in libvpx's order, which has
		// regular and smooth the other way round than the bitstream, as
		// FFmpeg maps it.
		uint32_t InterpolationFilter(uint8_t coded)
		{
			return coded <= 1 ? coded ^ 1u : coded;
		}
	}

	VaapiVp9Decoder::VaapiVp9Decoder(VaapiLibrary & library) :
		library(library),
		va(library.Api()),
		implementationName("VA-API (" + library.Vendor() + ")"),
		callback(nullptr),
		config(VA_INVALID_ID),
		context(VA_INVALID_ID),
		width(0),
		height(0),
		downloadImage(),
		started(false),
		failed(false),
		layered(false)
	{
		downloadImage.image_id = VA_INVALID_ID;
		slots.fill(-1);
	}

	VaapiVp9Decoder::~VaapiVp9Decoder()
	{
		Release();
	}

	bool VaapiVp9Decoder::Check(VAStatus status, const char * operation) const
	{
		if (status == VA_STATUS_SUCCESS) {
			return true;
		}

		RTC_LOG(LS_WARNING) << implementationName << " VP9: " << operation << " failed: "
			<< library.ErrorString(status);

		return false;
	}

	bool VaapiVp9Decoder::Configure(const Settings & settings)
	{
		if (settings.codec_type() != webrtc::kVideoCodecVP9) {
			return false;
		}

		Release();

		RTC_LOG(LS_INFO) << implementationName << " VP9 decoder configured";

		return true;
	}

	int32_t VaapiVp9Decoder::Decode(const webrtc::EncodedImage & image, int64_t renderTimeMs)
	{
		if (callback == nullptr) {
			return WEBRTC_VIDEO_CODEC_UNINITIALIZED;
		}
		if (image.size() == 0) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}
		if (failed) {
			return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
		}

		// Decoding starts with a key frame. Until one has come, WebRTC is
		// asked for it.
		if (!started && image.FrameType() != webrtc::VideoFrameType::kVideoFrameKey) {
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		const std::span<const uint8_t> data(image.data(), image.size());
		const int topLayer = image.SpatialIndex().value_or(0);
		std::vector<std::span<const uint8_t>> frames;

		// The spatial layers of a frame come back to back, without the
		// superframe index that would tell where one ends; WebRTC gives the
		// size of each instead, and none for a layer the encoder left out.
		if (topLayer > 0) {
			layered = true;

			size_t offset = 0;

			for (int layer = 0; layer <= topLayer; layer++) {
				const std::optional<size_t> size = image.SpatialLayerFrameSize(layer);

				if (!size || *size == 0) {
					continue;
				}
				if (*size > data.size() - offset) {
					RTC_LOG(LS_WARNING) << implementationName << " VP9: spatial layers larger than the frame";
					failed = true;

					return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
				}

				for (std::span<const uint8_t> frame : Vp9FrameHeaderParser::SplitSuperframe(data.subspan(offset, *size))) {
					frames.push_back(frame);
				}

				offset += *size;
			}

			if (offset != data.size()) {
				RTC_LOG(LS_WARNING) << implementationName << " VP9: spatial layers that do not add up to the frame";
				failed = true;

				return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
			}
		}
		else {
			frames = Vp9FrameHeaderParser::SplitSuperframe(data);
		}

		if (frames.empty()) {
			RTC_LOG(LS_WARNING) << implementationName << " VP9: a superframe with a broken index";
			failed = true;

			return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
		}

		// Of the frames shown, the last is the picture: the highest spatial
		// layer, as libvpx shows it.
		int shown = -1;

		for (std::span<const uint8_t> frame : frames) {
			const std::optional<Vp9FrameHeader> header = parser.Parse(frame);

			if (!header) {
				RTC_LOG(LS_INFO) << implementationName << " VP9: a frame that is not 8 bit 4:2:0 profile 0, "
					"or refers to a frame that is not there";
				failed = true;

				return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
			}

			if (header->showExistingFrame) {
				if (slots[header->frameToShow] < 0) {
					failed = true;

					return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
				}

				shown = slots[header->frameToShow];
				continue;
			}

			if (header->keyFrame) {
				// The surfaces take the largest frame of the picture, so that
				// the spatial layers above a key frame fit.
				uint32_t largestWidth = header->width;
				uint32_t largestHeight = header->height;

				for (std::span<const uint8_t> other : frames) {
					std::optional<std::pair<uint32_t, uint32_t>> size = Vp9FrameHeaderParser::FrameSize(other);

					if (size) {
						largestWidth = std::max(largestWidth, size->first);
						largestHeight = std::max(largestHeight, size->second);
					}
				}

				// With spatial layers, surfaces that take the picture stay, so
				// that a layer WebRTC adds back later fits without a key frame.
				const bool fits = layered && largestWidth <= width && largestHeight <= height;

				if (context == VA_INVALID_ID || (!fits && (largestWidth != width || largestHeight != height))) {
					if (!CreateSession(largestWidth, largestHeight)) {
						failed = true;

						return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
					}
				}
			}
			else if (context == VA_INVALID_ID) {
				failed = true;

				return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
			}
			else if (header->width > width || header->height > height) {
				// A spatial layer that WebRTC adds without a key frame, larger
				// than the surfaces. The key frame WebRTC is asked for carries
				// all layers, and the surfaces are made to take them.
				RTC_LOG(LS_INFO) << implementationName << " VP9: a spatial layer of " << header->width << "x"
					<< header->height << " does not fit, asking for a key frame";

				return WEBRTC_VIDEO_CODEC_ERROR;
			}
			// The hardware scales references of another size.

			const int surface = FreeSurface(shown);

			if (surface < 0 || !DecodeFrame(*header, frame, surface)) {
				failed = true;

				return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
			}

			frameSizes[surface] = { header->width, header->height };

			for (int slot = 0; slot < 8; slot++) {
				if (header->refreshFrameFlags & (1 << slot)) {
					slots[slot] = surface;
				}
			}

			started = true;

			if (header->showFrame) {
				shown = surface;
			}
		}

		if (shown >= 0 && !Output(shown, image, renderTimeMs)) {
			failed = true;

			return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
		}

		return WEBRTC_VIDEO_CODEC_OK;
	}

	int32_t VaapiVp9Decoder::RegisterDecodeCompleteCallback(webrtc::DecodedImageCallback * decodeCallback)
	{
		callback = decodeCallback;

		return WEBRTC_VIDEO_CODEC_OK;
	}

	int32_t VaapiVp9Decoder::Release()
	{
		DestroySession();

		parser.Reset();
		started = false;
		failed = false;
		layered = false;

		return WEBRTC_VIDEO_CODEC_OK;
	}

	webrtc::VideoDecoder::DecoderInfo VaapiVp9Decoder::GetDecoderInfo() const
	{
		DecoderInfo info;
		info.implementation_name = implementationName;
		info.is_hardware_accelerated = true;

		return info;
	}

	const char * VaapiVp9Decoder::ImplementationName() const
	{
		return implementationName.c_str();
	}

	bool VaapiVp9Decoder::CreateSession(uint32_t frameWidth, uint32_t frameHeight)
	{
		DestroySession();

		VADisplay display = library.Display();

		VAConfigAttrib attribute = {};
		attribute.type = VAConfigAttribRTFormat;
		attribute.value = VA_RT_FORMAT_YUV420;

		if (!Check(va.CreateConfig(display, VAProfileVP9Profile0, VAEntrypointVLD, &attribute, 1, &config),
			"creating the configuration"))
		{
			config = VA_INVALID_ID;
			return false;
		}

		surfaces.assign(kSurfaceCount, VA_INVALID_SURFACE);

		if (!Check(va.CreateSurfaces(display, VA_RT_FORMAT_YUV420, frameWidth, frameHeight, surfaces.data(),
			kSurfaceCount, nullptr, 0), "creating surfaces"))
		{
			surfaces.clear();
			DestroySession();
			return false;
		}

		if (!Check(va.CreateContext(display, config, static_cast<int>(frameWidth), static_cast<int>(frameHeight),
			VA_PROGRESSIVE, surfaces.data(), kSurfaceCount, &context), "creating the context"))
		{
			context = VA_INVALID_ID;
			DestroySession();
			return false;
		}

		// Copying a surface into an image reads it from cached memory, which
		// mapping it directly does not. Where the driver does not copy, the
		// surface is mapped.
		VAImageFormat format = {};
		format.fourcc = VA_FOURCC_NV12;
		format.byte_order = VA_LSB_FIRST;
		format.bits_per_pixel = 12;

		if (va.CreateImage(display, &format, static_cast<int>(frameWidth), static_cast<int>(frameHeight),
			&downloadImage) != VA_STATUS_SUCCESS)
		{
			downloadImage.image_id = VA_INVALID_ID;
		}

		width = frameWidth;
		height = frameHeight;
		frameSizes.assign(kSurfaceCount, { frameWidth, frameHeight });

		RTC_LOG(LS_INFO) << implementationName << " VP9 decoding " << width << "x" << height;

		return true;
	}

	void VaapiVp9Decoder::DestroySession()
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
		frameSizes.clear();
		slots.fill(-1);
	}

	int VaapiVp9Decoder::FreeSurface(int keep) const
	{
		for (int surface = 0; surface < static_cast<int>(surfaces.size()); surface++) {
			if (surface != keep && std::find(slots.begin(), slots.end(), surface) == slots.end()) {
				return surface;
			}
		}

		return -1;
	}

	bool VaapiVp9Decoder::DecodeFrame(const Vp9FrameHeader & header, std::span<const uint8_t> frame, int surface)
	{
		if (header.uncompressedHeaderSize > UINT8_MAX) {
			return false;
		}

		VADecPictureParameterBufferVP9 picture = {};
		picture.frame_width = static_cast<uint16_t>(header.width);
		picture.frame_height = static_cast<uint16_t>(header.height);

		for (int slot = 0; slot < 8; slot++) {
			picture.reference_frames[slot] = slots[slot] >= 0 ? surfaces[slots[slot]] : VA_INVALID_SURFACE;
		}

		auto & fields = picture.pic_fields.bits;
		fields.subsampling_x = header.subsamplingX ? 1 : 0;
		fields.subsampling_y = header.subsamplingY ? 1 : 0;
		fields.frame_type = header.keyFrame ? 0 : kInterFrame;
		fields.show_frame = header.showFrame ? 1 : 0;
		fields.error_resilient_mode = header.errorResilient ? 1 : 0;
		fields.intra_only = header.intraOnly ? 1 : 0;
		fields.allow_high_precision_mv = header.allowHighPrecisionMv ? 1 : 0;
		fields.mcomp_filter_type = InterpolationFilter(header.interpolationFilter);
		fields.frame_parallel_decoding_mode = header.frameParallelDecoding ? 1 : 0;
		fields.reset_frame_context = header.resetFrameContext;
		fields.refresh_frame_context = header.refreshFrameContext ? 1 : 0;
		fields.frame_context_idx = header.frameContextIndex;
		fields.segmentation_enabled = header.segmentationEnabled ? 1 : 0;
		fields.segmentation_temporal_update = header.segmentationTemporalUpdate ? 1 : 0;
		fields.segmentation_update_map = header.segmentationUpdateMap ? 1 : 0;
		fields.last_ref_frame = header.referenceSlots[0];
		fields.last_ref_frame_sign_bias = header.referenceSignBias[0] ? 1 : 0;
		fields.golden_ref_frame = header.referenceSlots[1];
		fields.golden_ref_frame_sign_bias = header.referenceSignBias[1] ? 1 : 0;
		fields.alt_ref_frame = header.referenceSlots[2];
		fields.alt_ref_frame_sign_bias = header.referenceSignBias[2] ? 1 : 0;
		fields.lossless_flag = header.lossless ? 1 : 0;

		picture.filter_level = header.filterLevel;
		picture.sharpness_level = header.sharpness;
		picture.log2_tile_rows = header.log2TileRows;
		picture.log2_tile_columns = header.log2TileColumns;
		picture.frame_header_length_in_bytes = static_cast<uint8_t>(header.uncompressedHeaderSize);
		picture.first_partition_size = static_cast<uint16_t>(header.compressedHeaderSize);
		std::copy(header.segmentationTreeProbs.begin(), header.segmentationTreeProbs.end(),
			picture.mb_segment_tree_probs);
		std::copy(header.segmentationPredProbs.begin(), header.segmentationPredProbs.end(),
			picture.segment_pred_probs);
		picture.profile = header.profile;
		picture.bit_depth = header.bitDepth;

		VASliceParameterBufferVP9 slice = {};
		slice.slice_data_size = static_cast<uint32_t>(frame.size());
		slice.slice_data_offset = 0;
		slice.slice_data_flag = VA_SLICE_DATA_FLAG_ALL;

		for (int i = 0; i < 8; i++) {
			const Vp9Segment & segment = header.segments[i];
			VASegmentParameterVP9 & target = slice.seg_param[i];

			target.segment_flags.fields.segment_reference_enabled = segment.referenceEnabled ? 1 : 0;
			target.segment_flags.fields.segment_reference = segment.reference;
			target.segment_flags.fields.segment_reference_skipped = segment.skipped ? 1 : 0;

			for (int reference = 0; reference < 4; reference++) {
				target.filter_level[reference][0] = segment.filterLevel[reference][0];
				target.filter_level[reference][1] = segment.filterLevel[reference][1];
			}

			target.luma_dc_quant_scale = segment.lumaDcScale;
			target.luma_ac_quant_scale = segment.lumaAcScale;
			target.chroma_dc_quant_scale = segment.chromaDcScale;
			target.chroma_ac_quant_scale = segment.chromaAcScale;
		}

		VADisplay display = library.Display();
		VABufferID buffers[3] = { VA_INVALID_ID, VA_INVALID_ID, VA_INVALID_ID };

		bool created = Check(va.CreateBuffer(display, context, VAPictureParameterBufferType, sizeof(picture), 1,
			&picture, &buffers[0]), "creating the picture parameters")
			&& Check(va.CreateBuffer(display, context, VASliceParameterBufferType, sizeof(slice), 1, &slice,
				&buffers[1]), "creating the slice parameters")
			&& Check(va.CreateBuffer(display, context, VASliceDataBufferType, static_cast<unsigned int>(frame.size()),
				1, const_cast<uint8_t *>(frame.data()), &buffers[2]), "creating the slice data");

		bool decoded = false;

		if (created && Check(va.BeginPicture(display, context, surfaces[surface]), "beginning a picture")) {
			const bool rendered = Check(va.RenderPicture(display, context, buffers, 3), "rendering a picture");
			// A picture that was begun has to be ended.
			const bool ended = Check(va.EndPicture(display, context), "ending a picture");

			decoded = rendered && ended && Check(va.SyncSurface(display, surfaces[surface]), "waiting for a picture");
		}

		for (VABufferID buffer : buffers) {
			if (buffer != VA_INVALID_ID) {
				va.DestroyBuffer(display, buffer);
			}
		}

		return decoded;
	}

	bool VaapiVp9Decoder::Output(int surface, const webrtc::EncodedImage & image, int64_t renderTimeMs)
	{
		VADisplay display = library.Display();
		const int frameWidth = static_cast<int>(frameSizes[surface].first);
		const int frameHeight = static_cast<int>(frameSizes[surface].second);

		VAImage mapped = {};
		mapped.image_id = VA_INVALID_ID;
		bool derived = false;

		if (downloadImage.image_id != VA_INVALID_ID
			&& va.GetImage(display, surfaces[surface], 0, 0, frameSizes[surface].first,
				frameSizes[surface].second, downloadImage.image_id)
				== VA_STATUS_SUCCESS)
		{
			mapped = downloadImage;
		}
		else if (va.DeriveImage(display, surfaces[surface], &mapped) == VA_STATUS_SUCCESS) {
			derived = true;

			if (mapped.format.fourcc != VA_FOURCC_NV12) {
				RTC_LOG(LS_WARNING) << implementationName << " VP9: the decoded surface is not NV12";
				va.DestroyImage(display, mapped.image_id);
				return false;
			}
		}
		else {
			RTC_LOG(LS_WARNING) << implementationName << " VP9: the decoded surface cannot be read";
			return false;
		}

		void * data = nullptr;
		bool converted = false;
		webrtc::scoped_refptr<webrtc::I420Buffer> i420 = webrtc::I420Buffer::Create(frameWidth, frameHeight);

		if (Check(va.MapBuffer(display, mapped.buf, &data), "mapping a decoded picture")) {
			const uint8_t * base = static_cast<const uint8_t *>(data);

			converted = libyuv::NV12ToI420(base + mapped.offsets[0], static_cast<int>(mapped.pitches[0]),
				base + mapped.offsets[1], static_cast<int>(mapped.pitches[1]),
				i420->MutableDataY(), i420->StrideY(), i420->MutableDataU(), i420->StrideU(),
				i420->MutableDataV(), i420->StrideV(), frameWidth, frameHeight) == 0;

			va.UnmapBuffer(display, mapped.buf);
		}

		if (derived) {
			va.DestroyImage(display, mapped.image_id);
		}

		if (!converted) {
			return false;
		}

		webrtc::VideoFrame frame = webrtc::VideoFrame::Builder()
			.set_video_frame_buffer(i420)
			.set_rtp_timestamp(image.RtpTimestamp())
			.set_timestamp_ms(renderTimeMs)
			.set_ntp_time_ms(image.ntp_time_ms_)
			.set_rotation(image.rotation_)
			.build();

		callback->Decoded(frame, std::nullopt, std::nullopt);

		return true;
	}
}
