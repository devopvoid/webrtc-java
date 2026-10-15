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

#include "media/video/codec/linux/VaapiAv1Decoder.h"

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
	}

	VaapiAv1Decoder::VaapiAv1Decoder(VaapiLibrary & library) :
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
		failed(false)
	{
		downloadImage.image_id = VA_INVALID_ID;
		slots.fill(-1);
	}

	VaapiAv1Decoder::~VaapiAv1Decoder()
	{
		Release();
	}

	bool VaapiAv1Decoder::Check(VAStatus status, const char * operation) const
	{
		if (status == VA_STATUS_SUCCESS) {
			return true;
		}

		RTC_LOG(LS_WARNING) << implementationName << " AV1: " << operation << " failed: "
			<< library.ErrorString(status);

		return false;
	}

	bool VaapiAv1Decoder::Configure(const Settings & settings)
	{
		if (settings.codec_type() != webrtc::kVideoCodecAV1) {
			return false;
		}

		Release();

		RTC_LOG(LS_INFO) << implementationName << " AV1 decoder configured";

		return true;
	}

	int32_t VaapiAv1Decoder::Decode(const webrtc::EncodedImage & image, int64_t renderTimeMs)
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

		std::vector<Frame> frames;

		if (!ParseTemporalUnit(std::span<const uint8_t>(image.data(), image.size()), frames)
			|| !DecodeFrames(frames, image, renderTimeMs))
		{
			failed = true;

			return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
		}

		return WEBRTC_VIDEO_CODEC_OK;
	}

	int32_t VaapiAv1Decoder::RegisterDecodeCompleteCallback(webrtc::DecodedImageCallback * decodeCallback)
	{
		callback = decodeCallback;

		return WEBRTC_VIDEO_CODEC_OK;
	}

	int32_t VaapiAv1Decoder::Release()
	{
		DestroySession();

		parser.Reset();
		started = false;
		failed = false;

		return WEBRTC_VIDEO_CODEC_OK;
	}

	webrtc::VideoDecoder::DecoderInfo VaapiAv1Decoder::GetDecoderInfo() const
	{
		DecoderInfo info;
		info.implementation_name = implementationName;
		info.is_hardware_accelerated = true;

		return info;
	}

	const char * VaapiAv1Decoder::ImplementationName() const
	{
		return implementationName.c_str();
	}

	bool VaapiAv1Decoder::ParseTemporalUnit(std::span<const uint8_t> data, std::vector<Frame> & frames)
	{
		const auto obus = parser.SplitObus(data);

		if (!obus) {
			RTC_LOG(LS_WARNING) << implementationName << " AV1: OBUs that do not fit into the frame";
			return false;
		}

		for (const Av1Obu & obu : *obus) {
			if (obu.spatialId > 0) {
				// Spatial layers predict from frames of another size.
				RTC_LOG(LS_INFO) << implementationName << " AV1: a stream with spatial layers";
				return false;
			}
		}

		// The size the frames are decoded at, which a key frame may change.
		uint32_t frameWidth = context != VA_INVALID_ID ? width : 0;
		uint32_t frameHeight = context != VA_INVALID_ID ? height : 0;

		// The frame whose tiles are coming.
		std::optional<Frame> pending;

		// Takes a tile group of the pending frame in, and the frame into the
		// reference slots once it has all of its tiles.
		auto addTileGroup = [&](std::span<const uint8_t> payload) {
			if (!pending || !AddTileGroup(payload, *pending)) {
				return false;
			}

			const Av1FrameHeader & header = pending->header;

			if (pending->tiles.size() == size_t(header.tileCols) * header.tileRows) {
				parser.UpdateReferences(header);
				frames.push_back(std::move(*pending));
				pending.reset();
			}

			return true;
		};

		for (const Av1Obu & obu : *obus) {
			switch (obu.type) {
				case Av1ObuType::SequenceHeader: {
					if (!parser.ParseSequenceHeader(obu.payload)) {
						RTC_LOG(LS_WARNING) << implementationName << " AV1: a broken sequence header";
						return false;
					}

					const Av1SequenceHeader & sequence = *parser.SequenceHeader();

					if (sequence.profile != 0 || sequence.bitDepth != 8 || sequence.monochrome) {
						RTC_LOG(LS_INFO) << implementationName << " AV1: a stream that is not 8 bit 4:2:0 profile 0";
						return false;
					}
					break;
				}

				case Av1ObuType::FrameHeader:
				case Av1ObuType::RedundantFrameHeader:
				case Av1ObuType::Frame: {
					if (pending) {
						// Copies of the header of the frame whose tiles are
						// coming; a frame OBU has to start a frame.
						if (obu.type == Av1ObuType::Frame) {
							return false;
						}
						break;
					}

					const std::optional<Av1FrameHeader> header = parser.ParseFrameHeader(obu.payload, obu);

					if (!header) {
						RTC_LOG(LS_INFO) << implementationName << " AV1: a broken frame header, or one that "
							"refers to a frame that is not there";
						return false;
					}

					if (header->showExistingFrame) {
						if (obu.type == Av1ObuType::Frame) {
							return false;
						}

						parser.UpdateReferences(*header);
						frames.push_back(Frame { *header });
						break;
					}

					if (header->applyGrain) {
						RTC_LOG(LS_INFO) << implementationName << " AV1: a frame with film grain";
						return false;
					}
					if (header->useSuperres) {
						RTC_LOG(LS_INFO) << implementationName << " AV1: a frame with superres";
						return false;
					}

					if (header->upscaledWidth != frameWidth || header->frameHeight != frameHeight) {
						// A key frame that is shown starts a new session, as it
						// replaces all references; frames that scale their
						// references are left to dav1d.
						if (header->frameType != kAv1KeyFrame || !header->showFrame) {
							RTC_LOG(LS_INFO) << implementationName << " AV1: a frame changes the size to "
								<< header->upscaledWidth << "x" << header->frameHeight;
							return false;
						}

						frameWidth = header->upscaledWidth;
						frameHeight = header->frameHeight;
					}

					pending = Frame { *header };

					if (obu.type == Av1ObuType::Frame) {
						if (header->headerBytes > obu.payload.size()
							|| !addTileGroup(obu.payload.subspan(header->headerBytes)))
						{
							return false;
						}
					}
					break;
				}

				case Av1ObuType::TileGroup:
					if (!addTileGroup(obu.payload)) {
						return false;
					}
					break;

				default:
					// Temporal delimiters, metadata and padding.
					break;
			}
		}

		if (pending) {
			RTC_LOG(LS_WARNING) << implementationName << " AV1: a frame without all of its tiles";
			return false;
		}

		return true;
	}

	bool VaapiAv1Decoder::AddTileGroup(std::span<const uint8_t> payload, Frame & frame)
	{
		const std::optional<Av1TileGroup> group = parser.ParseTileGroup(payload, frame.header);

		if (!group || group->start != frame.tiles.size()) {
			RTC_LOG(LS_WARNING) << implementationName << " AV1: a broken tile group, or one out of order";
			return false;
		}

		const uint32_t base = static_cast<uint32_t>(frame.tileData.size());

		frame.tileData.insert(frame.tileData.end(), payload.begin(), payload.end());

		for (const Av1Tile & tile : group->tiles) {
			VASliceParameterBufferAV1 slice = {};
			slice.slice_data_size = tile.size;
			slice.slice_data_offset = base + tile.offset;
			slice.slice_data_flag = VA_SLICE_DATA_FLAG_ALL;
			slice.tile_row = tile.row;
			slice.tile_column = tile.column;

			frame.tiles.push_back(slice);
		}

		return true;
	}

	bool VaapiAv1Decoder::DecodeFrames(const std::vector<Frame> & frames, const webrtc::EncodedImage & image,
		int64_t renderTimeMs)
	{
		for (const Frame & frame : frames) {
			const Av1FrameHeader & header = frame.header;

			if (header.showExistingFrame) {
				const int surface = slots[header.frameToShow];

				if (surface < 0 || !Output(surface, image, renderTimeMs)) {
					return false;
				}

				// A key frame shown again takes all reference slots.
				if (header.frameType == kAv1KeyFrame) {
					slots.fill(surface);
				}

				continue;
			}

			if (context == VA_INVALID_ID || header.upscaledWidth != width || header.frameHeight != height) {
				if (!CreateSession(header.upscaledWidth, header.frameHeight)) {
					return false;
				}
			}

			const int surface = FreeSurface();

			if (surface < 0 || !DecodeFrame(frame, surface)) {
				return false;
			}

			for (int slot = 0; slot < 8; slot++) {
				if (header.refreshFrameFlags & (1 << slot)) {
					slots[slot] = surface;
				}
			}

			started = true;

			if (header.showFrame && !Output(surface, image, renderTimeMs)) {
				return false;
			}
		}

		return true;
	}

	bool VaapiAv1Decoder::CreateSession(uint32_t frameWidth, uint32_t frameHeight)
	{
		DestroySession();

		VADisplay display = library.Display();

		VAConfigAttrib attribute = {};
		attribute.type = VAConfigAttribRTFormat;
		attribute.value = VA_RT_FORMAT_YUV420;

		if (!Check(va.CreateConfig(display, VAProfileAV1Profile0, VAEntrypointVLD, &attribute, 1, &config),
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

		RTC_LOG(LS_INFO) << implementationName << " AV1 decoding " << width << "x" << height;

		return true;
	}

	void VaapiAv1Decoder::DestroySession()
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
		slots.fill(-1);
	}

	int VaapiAv1Decoder::FreeSurface() const
	{
		for (int surface = 0; surface < static_cast<int>(surfaces.size()); surface++) {
			if (std::find(slots.begin(), slots.end(), surface) == slots.end()) {
				return surface;
			}
		}

		return -1;
	}

	bool VaapiAv1Decoder::DecodeFrame(const Frame & frame, int surface)
	{
		const Av1FrameHeader & header = frame.header;
		const Av1SequenceHeader & sequence = *parser.SequenceHeader();

		VADecPictureParameterBufferAV1 picture = {};
		picture.profile = sequence.profile;
		picture.order_hint_bits_minus_1 = static_cast<uint8_t>(std::max(sequence.orderHintBits - 1, 0));
		picture.bit_depth_idx = 0;
		picture.matrix_coefficients = sequence.matrixCoefficients;

		auto & seqFields = picture.seq_info_fields.fields;
		seqFields.still_picture = sequence.stillPicture ? 1 : 0;
		seqFields.use_128x128_superblock = sequence.use128x128Superblock ? 1 : 0;
		seqFields.enable_filter_intra = sequence.enableFilterIntra ? 1 : 0;
		seqFields.enable_intra_edge_filter = sequence.enableIntraEdgeFilter ? 1 : 0;
		seqFields.enable_interintra_compound = sequence.enableInterintraCompound ? 1 : 0;
		seqFields.enable_masked_compound = sequence.enableMaskedCompound ? 1 : 0;
		seqFields.enable_dual_filter = sequence.enableDualFilter ? 1 : 0;
		seqFields.enable_order_hint = sequence.enableOrderHint ? 1 : 0;
		seqFields.enable_jnt_comp = sequence.enableJntComp ? 1 : 0;
		seqFields.enable_cdef = sequence.enableCdef ? 1 : 0;
		seqFields.mono_chrome = sequence.monochrome ? 1 : 0;
		seqFields.color_range = sequence.colorRange ? 1 : 0;
		seqFields.subsampling_x = sequence.subsamplingX ? 1 : 0;
		seqFields.subsampling_y = sequence.subsamplingY ? 1 : 0;
		seqFields.film_grain_params_present = sequence.filmGrainParamsPresent ? 1 : 0;

		picture.current_frame = surfaces[surface];
		picture.current_display_picture = surfaces[surface];
		picture.frame_width_minus1 = static_cast<uint16_t>(header.upscaledWidth - 1);
		picture.frame_height_minus1 = static_cast<uint16_t>(header.frameHeight - 1);

		// A key frame that is shown refers to nothing.
		const bool resets = header.frameType == kAv1KeyFrame && header.showFrame;

		for (int slot = 0; slot < 8; slot++) {
			picture.ref_frame_map[slot] = !resets && slots[slot] >= 0 ? surfaces[slots[slot]] : VA_INVALID_SURFACE;
		}
		for (int i = 0; i < 7; i++) {
			picture.ref_frame_idx[i] = header.refFrameIdx[i];
		}

		picture.primary_ref_frame = header.primaryRefFrame;
		picture.order_hint = header.orderHint;

		auto & segmentation = picture.seg_info;
		segmentation.segment_info_fields.bits.enabled = header.segmentationEnabled ? 1 : 0;
		segmentation.segment_info_fields.bits.update_map = header.segmentationUpdateMap ? 1 : 0;
		segmentation.segment_info_fields.bits.temporal_update = header.segmentationTemporalUpdate ? 1 : 0;
		segmentation.segment_info_fields.bits.update_data = header.segmentationUpdateData ? 1 : 0;

		for (int segment = 0; segment < 8; segment++) {
			for (int feature = 0; feature < 8; feature++) {
				segmentation.feature_mask[segment] |= (header.featureEnabled[segment][feature] ? 1 : 0) << feature;
				segmentation.feature_data[segment][feature] = header.featureData[segment][feature];
			}
		}

		picture.tile_cols = header.tileCols;
		picture.tile_rows = header.tileRows;

		for (int i = 0; i < header.tileCols && i < 63; i++) {
			picture.width_in_sbs_minus_1[i] = static_cast<uint16_t>(header.tileWidthSbs[i] - 1);
		}
		for (int i = 0; i < header.tileRows && i < 63; i++) {
			picture.height_in_sbs_minus_1[i] = static_cast<uint16_t>(header.tileHeightSbs[i] - 1);
		}

		picture.context_update_tile_id = header.contextUpdateTileId;

		auto & picFields = picture.pic_info_fields.bits;
		picFields.frame_type = header.frameType;
		picFields.show_frame = header.showFrame ? 1 : 0;
		picFields.showable_frame = header.showableFrame ? 1 : 0;
		picFields.error_resilient_mode = header.errorResilientMode ? 1 : 0;
		picFields.disable_cdf_update = header.disableCdfUpdate ? 1 : 0;
		picFields.allow_screen_content_tools = header.allowScreenContentTools ? 1 : 0;
		picFields.force_integer_mv = header.forceIntegerMv ? 1 : 0;
		picFields.allow_intrabc = header.allowIntrabc ? 1 : 0;
		picFields.use_superres = header.useSuperres ? 1 : 0;
		picFields.allow_high_precision_mv = header.allowHighPrecisionMv ? 1 : 0;
		picFields.is_motion_mode_switchable = header.isMotionModeSwitchable ? 1 : 0;
		picFields.use_ref_frame_mvs = header.useRefFrameMvs ? 1 : 0;
		picFields.disable_frame_end_update_cdf = header.disableFrameEndUpdateCdf ? 1 : 0;
		picFields.uniform_tile_spacing_flag = header.uniformTileSpacing ? 1 : 0;
		picFields.allow_warped_motion = header.allowWarpedMotion ? 1 : 0;
		picFields.large_scale_tile = 0;

		picture.superres_scale_denominator = header.superresDenom;
		picture.interp_filter = header.interpolationFilter;
		picture.filter_level[0] = header.loopFilterLevel[0];
		picture.filter_level[1] = header.loopFilterLevel[1];
		picture.filter_level_u = header.loopFilterLevel[2];
		picture.filter_level_v = header.loopFilterLevel[3];

		auto & loopFilterFields = picture.loop_filter_info_fields.bits;
		loopFilterFields.sharpness_level = header.loopFilterSharpness;
		loopFilterFields.mode_ref_delta_enabled = header.loopFilterDeltaEnabled ? 1 : 0;
		loopFilterFields.mode_ref_delta_update = header.loopFilterDeltaUpdate ? 1 : 0;

		std::copy(header.loopFilterRefDeltas.begin(), header.loopFilterRefDeltas.end(), picture.ref_deltas);
		std::copy(header.loopFilterModeDeltas.begin(), header.loopFilterModeDeltas.end(), picture.mode_deltas);

		picture.base_qindex = header.baseQIndex;
		picture.y_dc_delta_q = header.deltaQYDc;
		picture.u_dc_delta_q = header.deltaQUDc;
		picture.u_ac_delta_q = header.deltaQUAc;
		picture.v_dc_delta_q = header.deltaQVDc;
		picture.v_ac_delta_q = header.deltaQVAc;

		auto & qmatrixFields = picture.qmatrix_fields.bits;
		qmatrixFields.using_qmatrix = header.usingQmatrix ? 1 : 0;
		qmatrixFields.qm_y = header.qmY;
		qmatrixFields.qm_u = header.qmU;
		qmatrixFields.qm_v = header.qmV;

		auto & modeFields = picture.mode_control_fields.bits;
		modeFields.delta_q_present_flag = header.deltaQPresent ? 1 : 0;
		modeFields.log2_delta_q_res = header.deltaQRes;
		modeFields.delta_lf_present_flag = header.deltaLfPresent ? 1 : 0;
		modeFields.log2_delta_lf_res = header.deltaLfRes;
		modeFields.delta_lf_multi = header.deltaLfMulti ? 1 : 0;
		modeFields.tx_mode = header.txMode;
		modeFields.reference_select = header.referenceSelect ? 1 : 0;
		modeFields.reduced_tx_set_used = header.reducedTxSet ? 1 : 0;
		modeFields.skip_mode_present = header.skipModePresent ? 1 : 0;

		picture.cdef_damping_minus_3 = header.cdefDampingMinus3;
		picture.cdef_bits = header.cdefBits;

		for (int i = 0; i < 8; i++) {
			picture.cdef_y_strengths[i] = static_cast<uint8_t>((header.cdefYPriStrength[i] << 2)
				| header.cdefYSecStrength[i]);
			picture.cdef_uv_strengths[i] = static_cast<uint8_t>((header.cdefUvPriStrength[i] << 2)
				| header.cdefUvSecStrength[i]);
		}

		auto & restorationFields = picture.loop_restoration_fields.bits;
		restorationFields.yframe_restoration_type = header.frameRestorationType[0];
		restorationFields.cbframe_restoration_type = header.frameRestorationType[1];
		restorationFields.crframe_restoration_type = header.frameRestorationType[2];
		restorationFields.lr_unit_shift = header.lrUnitShift;
		restorationFields.lr_uv_shift = header.lrUvShift;

		for (int i = 0; i < 7; i++) {
			const int ref = i + 1;
			VAWarpedMotionParamsAV1 & warp = picture.wm[i];

			warp.wmtype = static_cast<VAAV1TransformationType>(header.gmType[ref]);

			for (int j = 0; j < 6; j++) {
				warp.wmmat[j] = header.gmParams[ref][j];
			}

			warp.invalid = header.gmValid[ref] ? 0 : 1;
		}

		VADisplay display = library.Display();
		VABufferID buffers[3] = { VA_INVALID_ID, VA_INVALID_ID, VA_INVALID_ID };

		// vaCreateBuffer copies the data, though it takes it as not const.
		auto * tiles = const_cast<VASliceParameterBufferAV1 *>(frame.tiles.data());
		auto * tileData = const_cast<uint8_t *>(frame.tileData.data());

		bool created = Check(va.CreateBuffer(display, context, VAPictureParameterBufferType, sizeof(picture), 1,
			&picture, &buffers[0]), "creating the picture parameters")
			&& Check(va.CreateBuffer(display, context, VASliceParameterBufferType, sizeof(VASliceParameterBufferAV1),
				static_cast<unsigned int>(frame.tiles.size()), tiles, &buffers[1]), "creating the tile parameters")
			&& Check(va.CreateBuffer(display, context, VASliceDataBufferType,
				static_cast<unsigned int>(frame.tileData.size()), 1, tileData, &buffers[2]), "creating the tile data");

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

	bool VaapiAv1Decoder::Output(int surface, const webrtc::EncodedImage & image, int64_t renderTimeMs)
	{
		VADisplay display = library.Display();
		const int frameWidth = static_cast<int>(width);
		const int frameHeight = static_cast<int>(height);

		VAImage mapped = {};
		mapped.image_id = VA_INVALID_ID;
		bool derived = false;

		if (downloadImage.image_id != VA_INVALID_ID
			&& va.GetImage(display, surfaces[surface], 0, 0, width, height, downloadImage.image_id)
				== VA_STATUS_SUCCESS)
		{
			mapped = downloadImage;
		}
		else if (va.DeriveImage(display, surfaces[surface], &mapped) == VA_STATUS_SUCCESS) {
			derived = true;

			if (mapped.format.fourcc != VA_FOURCC_NV12) {
				RTC_LOG(LS_WARNING) << implementationName << " AV1: the decoded surface is not NV12";
				va.DestroyImage(display, mapped.image_id);
				return false;
			}
		}
		else {
			RTC_LOG(LS_WARNING) << implementationName << " AV1: the decoded surface cannot be read";
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
