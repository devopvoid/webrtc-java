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

#include "media/video/codec/linux/VaapiH265Encoder.h"

#include "api/video/encoded_image.h"
#include "modules/video_coding/include/video_codec_interface.h"
#include "modules/video_coding/include/video_error_codes.h"
#include "rtc_base/logging.h"

#include <algorithm>

namespace jni
{
	namespace
	{
		// Key frames come when WebRTC asks for them; this only bounds how
		// long a stream may go without one if it never does.
		constexpr uint32_t kIntraPeriod = 3000;

		// H.265 slice types (7.4.7.1) and NAL unit types (table 7-1).
		constexpr uint8_t kSliceTypeB = 0;
		constexpr uint8_t kSliceTypeP = 1;
		constexpr uint8_t kSliceTypeI = 2;
		constexpr uint8_t kNalTrailN = 0;
		constexpr uint8_t kNalTrailR = 1;
		constexpr uint8_t kNalIdrWRadl = 19;
		constexpr uint8_t kNalVps = 32;
		constexpr uint8_t kNalSps = 33;
		constexpr uint8_t kNalPps = 34;

		// The coded picture is whole blocks of 16x16: AMD's encoder codes such
		// blocks and leaves the conformance window to the SPS it is given.
		constexpr uint32_t kPictureAlignment = 16;

		// AMD's encoder takes no picture narrower than 384 or lower than 128
		// luma samples; a smaller frame is coded in a picture of that size and
		// cropped, as WebRTC scales frames down that far.
		constexpr uint32_t kMinPictureWidth = 384;
		constexpr uint32_t kMinPictureHeight = 128;

		// slice_pic_order_cnt_lsb has 8 bits.
		constexpr uint32_t kLog2MaxPicOrderCntLsb = 8;

		// The packed headers the encoder writes where the driver takes both:
		// its slice headers go with its parameter sets only.
		constexpr uint32_t kPackedHeaders = VA_ENC_PACKED_HEADER_SEQUENCE | VA_ENC_PACKED_HEADER_SLICE;

		// VA-API's picture coding types.
		constexpr uint32_t kCodingTypeI = 1;
		constexpr uint32_t kCodingTypeP = 2;

		constexpr uint32_t kInitialQp = 26;

		// The lowest H.265 level whose limits a stream fits in, going by its
		// picture size and luma sample rate, per table A.8 of the standard;
		// general_level_idc is 30 times the level.
		uint8_t LevelIdc(uint32_t pictureSize, uint32_t framerate)
		{
			struct Level
			{
				uint8_t idc;
				uint64_t maxLumaPs;
				uint64_t maxLumaSr;
			};

			static const Level levels[] = {
				{ 93, 983040, 33177600 },
				{ 120, 2228224, 66846720 },
				{ 123, 2228224, 133693440 },
				{ 150, 8912896, 267386880 },
				{ 153, 8912896, 534773760 },
				{ 156, 8912896, 1069547520 }
			};

			const uint64_t sampleRate = static_cast<uint64_t>(pictureSize) * framerate;

			for (const Level & level : levels) {
				if (pictureSize <= level.maxLumaPs && sampleRate <= level.maxLumaSr) {
					return level.idc;
				}
			}

			return 156;
		}

		void InvalidatePicture(VAPictureHEVC & picture)
		{
			picture = {};
			picture.picture_id = VA_INVALID_SURFACE;
			picture.flags = VA_PICTURE_HEVC_INVALID;
		}

		// A coding tool is used where the driver allows it.
		bool Allowed(uint32_t feature)
		{
			return feature != VA_FEATURE_NOT_SUPPORTED;
		}
	}

	VaapiH265Encoder::VaapiH265Encoder(VaapiLibrary & library, VAEntrypoint entrypoint,
		const webrtc::SdpVideoFormat & format) :
		library(library),
		entrypoint(entrypoint),
		implementationName("VA-API (" + library.Vendor() + ")"),
		session(library, "VA-API H.265"),
		// FFmpeg's choice for a driver that does not say: 32x32 coding tree
		// blocks, coding blocks of at least 16x16, transform blocks of 4x4 to
		// 32x32 and three levels of transform hierarchy.
		log2CtbSize(5),
		log2MinCbSize(4),
		log2MinTbSize(2),
		log2MaxTbSize(5),
		maxTransformDepthInter(3),
		maxTransformDepthIntra(3),
		amp(true),
		sao(true),
		signDataHiding(false),
		transformSkip(false),
		cuQpDelta(true),
		packedHeaders(VA_ENC_PACKED_HEADER_NONE),
		lowDelayB(false),
		initialized(false),
		codecSettings(),
		codedWidth(0),
		codedHeight(0),
		widthInCtbs(0),
		heightInCtbs(0),
		bitrateBps(0),
		framerate(30),
		ratesChanged(false),
		pictureOrder(0),
		slotOrders{ 0, 0 },
		slotTemporalIds{ 0, 0 },
		referenceValid(false),
		callback(nullptr),
		outputProcessor(webrtc::kVideoCodecH265, format)
	{
		QueryCapabilities();
	}

	VaapiH265Encoder::~VaapiH265Encoder()
	{
		Release();
	}

	void VaapiH265Encoder::QueryCapabilities()
	{
		VAConfigAttrib attributes[4] = {};
		attributes[0].type = VAConfigAttribEncHEVCBlockSizes;
		attributes[1].type = VAConfigAttribEncHEVCFeatures;
		attributes[2].type = VAConfigAttribEncPackedHeaders;
		attributes[3].type = VAConfigAttribPredictionDirection;

		if (library.Api().GetConfigAttributes(library.Display(), VAProfileHEVCMain, entrypoint, attributes, 4) !=
			VA_STATUS_SUCCESS)
		{
			return;
		}

		if (attributes[0].value != VA_ATTRIB_NOT_SUPPORTED) {
			VAConfigAttribValEncHEVCBlockSizes sizes;
			sizes.value = attributes[0].value;

			log2CtbSize = sizes.bits.log2_max_coding_tree_block_size_minus3 + 3;
			log2MinCbSize = sizes.bits.log2_min_luma_coding_block_size_minus3 + 3;
			log2MinTbSize = sizes.bits.log2_min_luma_transform_block_size_minus2 + 2;
			log2MaxTbSize = sizes.bits.log2_max_luma_transform_block_size_minus2 + 2;
			maxTransformDepthInter = sizes.bits.max_max_transform_hierarchy_depth_inter;
			maxTransformDepthIntra = sizes.bits.max_max_transform_hierarchy_depth_intra;
		}

		if (attributes[1].value != VA_ATTRIB_NOT_SUPPORTED) {
			VAConfigAttribValEncHEVCFeatures features;
			features.value = attributes[1].value;

			amp = Allowed(features.bits.amp);
			sao = Allowed(features.bits.sao);
			signDataHiding = Allowed(features.bits.sign_data_hiding);
			transformSkip = Allowed(features.bits.transform_skip);
			cuQpDelta = Allowed(features.bits.cu_qp_delta);
		}

		if (attributes[2].value != VA_ATTRIB_NOT_SUPPORTED && (attributes[2].value & kPackedHeaders) == kPackedHeaders) {
			packedHeaders = kPackedHeaders;
		}

		if (attributes[3].value != VA_ATTRIB_NOT_SUPPORTED) {
			lowDelayB = (attributes[3].value & VA_PREDICTION_DIRECTION_BI_NOT_EMPTY) != 0;
		}
	}

	int32_t VaapiH265Encoder::InitEncode(const webrtc::VideoCodec * settings, const Settings & encoderSettings)
	{
		if (settings == nullptr || settings->width == 0 || settings->height == 0) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}
		if (settings->numberOfSimulcastStreams > 1) {
			return WEBRTC_VIDEO_CODEC_ERR_SIMULCAST_PARAMETERS_NOT_SUPPORTED;
		}

		// The frame is kept to whole coding blocks. WebRTC is asked for frames
		// of that alignment, but the first frames of a source can come before
		// it knows, so the encoder takes the aligned size below and drops the
		// rows and columns beyond it.
		const uint32_t alignment = 1u << log2MinCbSize;

		if (settings->width < alignment || settings->height < alignment) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}

		// Temporal layers, where the encoder writes the slice headers, which
		// carry the layers; spatial ones are left to the next codec.
		const webrtc::ScalabilityMode mode = settings->GetScalabilityMode().value_or(webrtc::ScalabilityMode::kL1T1);

		if (mode != webrtc::ScalabilityMode::kL1T1 && (packedHeaders != kPackedHeaders
			|| (mode != webrtc::ScalabilityMode::kL1T2 && mode != webrtc::ScalabilityMode::kL1T3)))
		{
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}

		Release();

		const uint32_t ctbSize = 1u << log2CtbSize;

		codecSettings = *settings;
		codecSettings.width -= codecSettings.width % alignment;
		codecSettings.height -= codecSettings.height % alignment;
		const uint32_t pictureAlignment = std::max(alignment, kPictureAlignment);
		codedWidth = std::max((codecSettings.width + pictureAlignment - 1) / pictureAlignment * pictureAlignment,
			kMinPictureWidth);
		codedHeight = std::max((codecSettings.height + pictureAlignment - 1) / pictureAlignment * pictureAlignment,
			kMinPictureHeight);
		widthInCtbs = (codedWidth + ctbSize - 1) / ctbSize;
		heightInCtbs = (codedHeight + ctbSize - 1) / ctbSize;
		bitrateBps = std::max(1u, codecSettings.startBitrate) * 1000;
		framerate = std::max(1u, codecSettings.maxFramerate);
		ratesChanged = true;
		pictureOrder = 0;
		referenceValid = false;
		outputProcessor.Reset();
		layers.Configure(mode, bitrateBps, VaapiTemporalLayers::LayerRateControl(library, VAProfileHEVCMain,
			entrypoint, webrtc::ScalabilityModeToNumTemporalLayers(mode)));

		if (!outputProcessor.SetScalabilityMode(mode)) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}

		// Surfaces of whole coding tree blocks, which the driver may write all
		// of.
		if (!session.Create(VAProfileHEVCMain, entrypoint, codedWidth, codedHeight, widthInCtbs << log2CtbSize,
			heightInCtbs << log2CtbSize, packedHeaders, VaapiTemporalLayers::kReconstructedSurfaces))
		{
			Release();
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		initialized = true;

		RTC_LOG(LS_INFO) << implementationName << " H.265 initialized: " << codecSettings.width << "x"
			<< codecSettings.height << " at " << bitrateBps << " bps, CTB " << ctbSize << ", min CB " << alignment << ", "
			<< webrtc::ScalabilityModeToString(mode);

		return WEBRTC_VIDEO_CODEC_OK;
	}

	int32_t VaapiH265Encoder::RegisterEncodeCompleteCallback(webrtc::EncodedImageCallback * encodeCallback)
	{
		callback = encodeCallback;

		return WEBRTC_VIDEO_CODEC_OK;
	}

	int32_t VaapiH265Encoder::Release()
	{
		session.Destroy();

		initialized = false;
		referenceValid = false;

		return WEBRTC_VIDEO_CODEC_OK;
	}

	int32_t VaapiH265Encoder::Encode(const webrtc::VideoFrame & frame,
		const std::vector<webrtc::VideoFrameType> * frameTypes)
	{
		if (!initialized || callback == nullptr) {
			return WEBRTC_VIDEO_CODEC_UNINITIALIZED;
		}

		bool idr = !referenceValid;

		if (frameTypes != nullptr) {
			idr |= std::any_of(frameTypes->begin(), frameTypes->end(), [](webrtc::VideoFrameType type) {
				return type == webrtc::VideoFrameType::kVideoFrameKey;
			});
		}

		if (idr) {
			pictureOrder = 0;
		}

		const auto layerFrame = outputProcessor.NextFrameConfig(idr);

		// No layer is encoded now, as when WebRTC gives none a bitrate.
		if (!layerFrame) {
			return WEBRTC_VIDEO_CODEC_OK;
		}

		const auto layerPlan = layers.Plan(idr, *layerFrame);

		if (!layerPlan) {
			RTC_LOG(LS_WARNING) << implementationName << " H.265: a picture refers to a slot that holds none";
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		const int alignment = 1 << log2MinCbSize;
		std::vector<uint8_t> output;

		const bool encoded = session.Upload(frame, static_cast<int>(codecSettings.width),
			static_cast<int>(codecSettings.height), alignment - 1) && Submit(idr, *layerPlan)
			&& session.ReadOutput(output);

		session.DropBuffers();


		if (!encoded) {
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		webrtc::EncodedImage image;
		image._encodedWidth = codecSettings.width;
		image._encodedHeight = codecSettings.height;
		image.SetRtpTimestamp(frame.rtp_timestamp());
		image.capture_time_ms_ = frame.render_time_ms();
		image.ntp_time_ms_ = frame.ntp_time_ms();
		image.rotation_ = frame.rotation();

		webrtc::CodecSpecificInfo info;

		// A driver that writes no parameter sets into key frames makes a
		// stream nothing can decode.
		if (!outputProcessor.Process(output, idr, *layerFrame, image, info)) {
			RTC_LOG(LS_WARNING) << implementationName << " H.265: the driver wrote no parameter sets";
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		// The slots the picture replaces hold it now.
		layers.Commit(*layerPlan);

		for (int slot = 0; slot < layers.SlotCount(); slot++) {
			if (layerPlan->keptFlags & (1 << slot)) {
				slotOrders[slot] = pictureOrder;
				slotTemporalIds[slot] = layerPlan->temporalId;
			}
			else if (slotTemporalIds[slot] > layerPlan->temporalId) {
				// Left out of the picture's reference picture set, so gone from
				// the decoder's.
				layers.Forget(slot);
			}
		}

		referenceValid = true;
		pictureOrder++;
		ratesChanged = false;

		callback->OnEncodedImage(image, &info);

		return WEBRTC_VIDEO_CODEC_OK;
	}

	bool VaapiH265Encoder::Submit(bool idr, const VaapiTemporalLayers::Frame & frame)
	{
		if (idr) {
			VAEncSequenceParameterBufferHEVC sequence = {};
			FillSequence(sequence);

			if (!session.AddBuffer(VAEncSequenceParameterBufferType, &sequence, sizeof(sequence))) {
				return false;
			}
		}

		if (idr || ratesChanged) {
			// Without layers, from the initial QP the encoder has always
			// started from.
			const bool added = layers.Count() == 1
				? session.AddRateControl(bitrateBps, framerate, kInitialQp)
				: layers.AddRateControl(session, bitrateBps, framerate, idr);

			if (!added) {
				return false;
			}
		}

		VAEncPictureParameterBufferHEVC picture = {};
		FillPicture(picture, idr, frame);

		VAEncSliceParameterBufferHEVC slice = {};
		FillSlice(slice, idr, frame);

		if (!session.AddBuffer(VAEncPictureParameterBufferType, &picture, sizeof(picture))) {
			return false;
		}

		if (idr && (packedHeaders & VA_ENC_PACKED_HEADER_SEQUENCE)) {
			std::vector<uint8_t> parameterSets;
			const size_t bits = WriteParameterSets(parameterSets);

			if (!session.AddPackedHeader(VAEncPackedHeaderSequence, parameterSets, bits)) {
				return false;
			}
		}

		if (packedHeaders & VA_ENC_PACKED_HEADER_SLICE) {
			std::vector<uint8_t> sliceHeader;
			const size_t bits = WriteSliceHeader(sliceHeader, idr, frame);

			if (!session.AddPackedHeader(VAEncPackedHeaderSlice, sliceHeader, bits)) {
				return false;
			}
		}

		return session.AddBuffer(VAEncSliceParameterBufferType, &slice, sizeof(slice)) && session.Encode();
	}

	void VaapiH265Encoder::FillSequence(VAEncSequenceParameterBufferHEVC & sequence) const
	{
		// Main profile, main tier.
		sequence.general_profile_idc = 1;
		sequence.general_level_idc = LevelIdc(codedWidth * codedHeight, framerate);
		sequence.general_tier_flag = 0;
		sequence.intra_period = kIntraPeriod;
		sequence.intra_idr_period = kIntraPeriod;
		sequence.ip_period = 1;
		sequence.bits_per_second = bitrateBps;
		sequence.pic_width_in_luma_samples = static_cast<uint16_t>(codedWidth);
		sequence.pic_height_in_luma_samples = static_cast<uint16_t>(codedHeight);

		sequence.seq_fields.bits.chroma_format_idc = 1;
		sequence.seq_fields.bits.amp_enabled_flag = amp ? 1 : 0;
		sequence.seq_fields.bits.sample_adaptive_offset_enabled_flag = sao ? 1 : 0;
		// No temporal motion vector prediction, which would need a collocated
		// picture to be named.
		sequence.seq_fields.bits.sps_temporal_mvp_enabled_flag = 0;
		sequence.seq_fields.bits.low_delay_seq = 1;

		sequence.log2_min_luma_coding_block_size_minus3 = static_cast<uint8_t>(log2MinCbSize - 3);
		sequence.log2_diff_max_min_luma_coding_block_size = static_cast<uint8_t>(log2CtbSize - log2MinCbSize);
		sequence.log2_min_transform_block_size_minus2 = static_cast<uint8_t>(log2MinTbSize - 2);
		sequence.log2_diff_max_min_transform_block_size = static_cast<uint8_t>(log2MaxTbSize - log2MinTbSize);
		sequence.max_transform_hierarchy_depth_inter = static_cast<uint8_t>(maxTransformDepthInter);
		sequence.max_transform_hierarchy_depth_intra = static_cast<uint8_t>(maxTransformDepthIntra);

		sequence.vui_parameters_present_flag = 1;
		sequence.vui_fields.bits.vui_timing_info_present_flag = 1;
		sequence.vui_num_units_in_tick = 1;
		sequence.vui_time_scale = framerate;
	}

	std::vector<VaapiH265Encoder::ReferencePicture> VaapiH265Encoder::ReferencePictureSet(
		const VaapiTemporalLayers::Frame & frame) const
	{
		std::vector<ReferencePicture> set;

		for (int slot = 0; slot < layers.SlotCount(); slot++) {
			// A picture of a higher layer, which a receiver without that layer
			// does not have.
			if (layers.Surface(slot) < 0 || slotTemporalIds[slot] > frame.temporalId) {
				continue;
			}

			const bool used = std::find(frame.references.begin(), frame.references.end(), slot)
				!= frame.references.end();
			auto same = std::find_if(set.begin(), set.end(), [&](const ReferencePicture & picture) {
				return layers.Surface(picture.slot) == layers.Surface(slot);
			});

			if (same != set.end()) {
				same->used |= used;
			}
			else {
				set.push_back({ slot, slotOrders[slot], used });
			}
		}

		std::sort(set.begin(), set.end(), [](const ReferencePicture & a, const ReferencePicture & b) {
			return a.pictureOrder > b.pictureOrder;
		});

		return set;
	}

	VAPictureHEVC VaapiH265Encoder::Picture(const ReferencePicture & reference) const
	{
		VAPictureHEVC picture = {};
		picture.picture_id = session.Reconstructed(layers.Surface(reference.slot));
		picture.pic_order_cnt = reference.pictureOrder;
		// A short-term reference before the current picture, which the
		// current one refers to or keeps for later.
		picture.flags = reference.used ? VA_PICTURE_HEVC_RPS_ST_CURR_BEFORE : 0;

		return picture;
	}

	void VaapiH265Encoder::FillPicture(VAEncPictureParameterBufferHEVC & picture, bool idr,
		const VaapiTemporalLayers::Frame & frame) const
	{
		picture.decoded_curr_pic.picture_id = session.Reconstructed(frame.surface);
		picture.decoded_curr_pic.pic_order_cnt = pictureOrder;
		picture.decoded_curr_pic.flags = 0;

		for (VAPictureHEVC & reference : picture.reference_frames) {
			InvalidatePicture(reference);
		}

		if (!idr) {
			const std::vector<ReferencePicture> set = ReferencePictureSet(frame);

			for (size_t i = 0; i < set.size(); i++) {
				picture.reference_frames[i] = Picture(set[i]);
			}
		}

		picture.coded_buf = session.CodedBuffer();
		// No collocated picture: there is no temporal motion vector
		// prediction.
		picture.collocated_ref_pic_index = 0xFF;
		picture.last_picture = 0;
		picture.pic_init_qp = kInitialQp;
		// The QP may change for every coding block of the smallest size: Intel's
		// low-power encoder changes it that often whatever it is told, and a
		// stream that says otherwise decodes with the wrong QP.
		picture.diff_cu_qp_delta_depth = static_cast<uint8_t>(CuQpDeltaDepth());
		picture.num_ref_idx_l0_default_active_minus1 = 0;
		picture.num_ref_idx_l1_default_active_minus1 = 0;
		picture.slice_pic_parameter_set_id = 0;
		// A picture no other refers to, as of the top layer, is a sub-layer
		// non-reference picture.
		const bool reference = idr || frame.refreshFlags != 0;
		picture.nal_unit_type = idr ? kNalIdrWRadl : (reference ? kNalTrailR : kNalTrailN);

		picture.pic_fields.bits.idr_pic_flag = idr ? 1 : 0;
		picture.pic_fields.bits.coding_type = idr ? kCodingTypeI : kCodingTypeP;
		picture.pic_fields.bits.reference_pic_flag = reference ? 1 : 0;
		picture.pic_fields.bits.sign_data_hiding_enabled_flag = signDataHiding ? 1 : 0;
		picture.pic_fields.bits.transform_skip_enabled_flag = transformSkip ? 1 : 0;
		// Needed for the rate control to change the QP within a picture.
		picture.pic_fields.bits.cu_qp_delta_enabled_flag = cuQpDelta ? 1 : 0;
	}

	void VaapiH265Encoder::FillSlice(VAEncSliceParameterBufferHEVC & slice, bool idr,
		const VaapiTemporalLayers::Frame & frame) const
	{
		slice.slice_segment_address = 0;
		slice.num_ctu_in_slice = widthInCtbs * heightInCtbs;
		slice.slice_type = idr ? kSliceTypeI : (lowDelayB ? kSliceTypeB : kSliceTypeP);
		slice.slice_pic_parameter_set_id = 0;
		slice.num_ref_idx_l0_active_minus1 = 0;
		slice.num_ref_idx_l1_active_minus1 = 0;

		for (VAPictureHEVC & reference : slice.ref_pic_list0) {
			InvalidatePicture(reference);
		}
		for (VAPictureHEVC & reference : slice.ref_pic_list1) {
			InvalidatePicture(reference);
		}

		if (!idr) {
			// The one picture the current one refers to.
			for (const ReferencePicture & reference : ReferencePictureSet(frame)) {
				if (reference.used) {
					slice.ref_pic_list0[0] = Picture(reference);

					if (lowDelayB) {
						slice.ref_pic_list1[0] = Picture(reference);
					}
					break;
				}
			}
		}

		slice.max_num_merge_cand = 5;
		slice.slice_qp_delta = 0;

		slice.slice_fields.bits.last_slice_of_pic_flag = 1;

		slice.slice_fields.bits.slice_sao_luma_flag = sao ? 1 : 0;
		slice.slice_fields.bits.slice_sao_chroma_flag = sao ? 1 : 0;
	}

	uint32_t VaapiH265Encoder::CuQpDeltaDepth() const
	{
		return cuQpDelta ? log2CtbSize - log2MinCbSize : 0;
	}

	void VaapiH265Encoder::WriteProfileTierLevel(NalUnitWriter & writer) const
	{
		// profile_tier_level(1, 0) (7.3.3): Main profile, main tier.
		writer.Bits(0, 2);
		writer.Flag(false);
		writer.Bits(1, 5);
		// Compatible with Main and Main 10.
		writer.Bits(0x60000000, 32);
		// Progressive frames only.
		writer.Flag(true);
		writer.Flag(false);
		writer.Flag(false);
		writer.Flag(true);
		// general_reserved_zero_43bits and general_inbld_flag.
		writer.Bits(0, 32);
		writer.Bits(0, 12);
		writer.Bits(LevelIdc(codedWidth * codedHeight, framerate), 8);

		// No profile or level of their own for the sub-layers.
		const int maxSubLayersMinus1 = layers.Count() - 1;

		for (int i = 0; i < maxSubLayersMinus1; i++) {
			writer.Flag(false);
			writer.Flag(false);
		}

		if (maxSubLayersMinus1 > 0) {
			for (int i = maxSubLayersMinus1; i < 8; i++) {
				writer.Bits(0, 2);
			}
		}
	}

	size_t VaapiH265Encoder::WriteParameterSets(std::vector<uint8_t> & output) const
	{
		const auto nalHeader = [](NalUnitWriter & writer, uint8_t type) {
			writer.Bits(0, 1);
			writer.Bits(type, 6);
			writer.Bits(0, 6);
			writer.Bits(1, 3);
		};

		// A sub-layer for each temporal layer. Without layers, the current
		// picture and one reference picture are decoded at a time; with
		// them, the current one and those of the two reference slots. There
		// is no reordering. The temporal id nesting flag is set without
		// layers, as the standard has it then, and left clear with them.
		const uint32_t maxSubLayersMinus1 = static_cast<uint32_t>(layers.Count() - 1);
		const uint32_t maxDecPicBufferingMinus1 = layers.Count() > 1 ? 2 : 1;
		const bool temporalIdNesting = layers.Count() == 1;

		// The VPS (7.3.2.1).
		NalUnitWriter vps;
		nalHeader(vps, kNalVps);
		vps.Bits(0, 4);
		vps.Flag(true);
		vps.Flag(true);
		vps.Bits(0, 6);
		vps.Bits(maxSubLayersMinus1, 3);
		vps.Flag(temporalIdNesting);
		vps.Bits(0xFFFF, 16);
		WriteProfileTierLevel(vps);
		// The ordering information of the highest sub-layer stands for all.
		vps.Flag(false);
		vps.Ue(maxDecPicBufferingMinus1);
		vps.Ue(0);
		vps.Ue(0);
		vps.Bits(0, 6);
		vps.Ue(0);
		vps.Flag(false);
		vps.Flag(false);
		vps.AlignWithStopBit();

		// The SPS (7.3.2.2) of FillSequence().
		NalUnitWriter sps;
		nalHeader(sps, kNalSps);
		sps.Bits(0, 4);
		sps.Bits(maxSubLayersMinus1, 3);
		sps.Flag(temporalIdNesting);
		WriteProfileTierLevel(sps);
		sps.Ue(0);
		sps.Ue(1);
		sps.Ue(codedWidth);
		sps.Ue(codedHeight);

		// The conformance window, in units of two luma samples for 4:2:0.
		const uint32_t croppedRight = codedWidth - codecSettings.width;
		const uint32_t croppedBottom = codedHeight - codecSettings.height;
		const bool cropped = croppedRight > 0 || croppedBottom > 0;

		sps.Flag(cropped);

		if (cropped) {
			sps.Ue(0);
			sps.Ue(croppedRight / 2);
			sps.Ue(0);
			sps.Ue(croppedBottom / 2);
		}

		sps.Ue(0);
		sps.Ue(0);
		sps.Ue(kLog2MaxPicOrderCntLsb - 4);
		sps.Flag(false);
		sps.Ue(maxDecPicBufferingMinus1);
		sps.Ue(0);
		sps.Ue(0);
		sps.Ue(log2MinCbSize - 3);
		sps.Ue(log2CtbSize - log2MinCbSize);
		sps.Ue(log2MinTbSize - 2);
		sps.Ue(log2MaxTbSize - log2MinTbSize);
		sps.Ue(maxTransformDepthInter);
		sps.Ue(maxTransformDepthIntra);
		sps.Flag(false);
		sps.Flag(amp);
		sps.Flag(sao);
		sps.Flag(false);
		// The reference picture sets are in the slice headers.
		sps.Ue(0);
		sps.Flag(false);
		sps.Flag(false);
		sps.Flag(false);

		// VUI (E.2.1) with the timing only.
		sps.Flag(true);
		sps.Flag(false);
		sps.Flag(false);
		sps.Flag(false);
		sps.Flag(false);
		sps.Flag(false);
		sps.Flag(false);
		sps.Flag(false);
		sps.Flag(false);
		sps.Flag(true);
		sps.Bits(1, 32);
		sps.Bits(framerate, 32);
		sps.Flag(false);
		sps.Flag(false);
		sps.Flag(false);

		sps.Flag(false);
		sps.AlignWithStopBit();

		// The PPS (7.3.2.3) of FillPicture().
		NalUnitWriter pps;
		nalHeader(pps, kNalPps);
		pps.Ue(0);
		pps.Ue(0);
		pps.Flag(false);
		pps.Flag(false);
		pps.Bits(0, 3);
		pps.Flag(signDataHiding);
		pps.Flag(false);
		pps.Ue(0);
		pps.Ue(0);
		pps.Se(static_cast<int32_t>(kInitialQp) - 26);
		pps.Flag(false);
		pps.Flag(transformSkip);
		pps.Flag(cuQpDelta);

		if (cuQpDelta) {
			pps.Ue(CuQpDeltaDepth());
		}

		pps.Se(0);
		pps.Se(0);
		// pps_slice_chroma_qp_offsets_present_flag, weighted_pred_flag,
		// weighted_bipred_flag, transquant_bypass_enabled_flag,
		// tiles_enabled_flag, entropy_coding_sync_enabled_flag,
		// pps_loop_filter_across_slices_enabled_flag,
		// deblocking_filter_control_present_flag,
		// pps_scaling_list_data_present_flag and
		// lists_modification_present_flag.
		for (int flag = 0; flag < 10; flag++) {
			pps.Flag(false);
		}

		pps.Ue(0);
		pps.Flag(false);
		pps.Flag(false);
		pps.AlignWithStopBit();

		return vps.AppendTo(output) + sps.AppendTo(output) + pps.AppendTo(output);
	}

	size_t VaapiH265Encoder::WriteSliceHeader(std::vector<uint8_t> & output, bool idr,
		const VaapiTemporalLayers::Frame & frame) const
	{
		// The slice segment header (7.3.6.1) of FillSlice(), up to the slice
		// data, which the driver writes, in a NAL unit of the picture's
		// temporal layer.
		const bool reference = idr || frame.refreshFlags != 0;

		NalUnitWriter header;
		header.Bits(0, 1);
		header.Bits(idr ? kNalIdrWRadl : (reference ? kNalTrailR : kNalTrailN), 6);
		header.Bits(0, 6);
		header.Bits(static_cast<uint32_t>(frame.temporalId + 1), 3);

		header.Flag(true);

		if (idr) {
			header.Flag(false);
		}

		header.Ue(0);
		header.Ue(idr ? kSliceTypeI : (lowDelayB ? kSliceTypeB : kSliceTypeP));

		if (!idr) {
			header.Bits(static_cast<uint32_t>(pictureOrder) & ((1u << kLog2MaxPicOrderCntLsb) - 1),
				kLog2MaxPicOrderCntLsb);
			// st_ref_pic_set(0) here, not in the SPS: the pictures of the
			// reference slots, before this one, of which it uses one. Those it
			// keeps for later may be missing at a receiver without the upper
			// layers, which the standard allows.
			const std::vector<ReferencePicture> set = ReferencePictureSet(frame);
			int32_t previous = pictureOrder;

			header.Flag(false);
			header.Ue(static_cast<uint32_t>(set.size()));
			header.Ue(0);

			for (const ReferencePicture & reference : set) {
				header.Ue(static_cast<uint32_t>(previous - reference.pictureOrder - 1));
				header.Flag(reference.used);
				previous = reference.pictureOrder;
			}
		}

		if (sao) {
			header.Flag(sao);
			header.Flag(sao);
		}

		if (!idr) {
			// num_ref_idx_active_override_flag, then mvd_l1_zero_flag of a B
			// slice, and five_minus_max_num_merge_cand.
			header.Flag(false);

			if (lowDelayB) {
				header.Flag(false);
			}

			header.Ue(0);
		}

		header.Se(0);
		header.AlignWithStopBit();

		return header.AppendTo(output);
	}

	void VaapiH265Encoder::SetRates(const RateControlParameters & parameters)
	{
		const uint32_t bitrate = parameters.bitrate.get_sum_bps();

		// A zero bitrate pauses the stream; the encoder gets no frames then.
		if (bitrate == 0) {
			return;
		}

		bitrateBps = bitrate;
		outputProcessor.OnRatesUpdated(parameters.bitrate);
		layers.OnRatesUpdated(parameters.bitrate);

		if (parameters.framerate_fps >= 1.0) {
			framerate = static_cast<uint32_t>(parameters.framerate_fps + 0.5);
		}

		// Applied with the next frame.
		ratesChanged = true;
	}

	webrtc::VideoEncoder::EncoderInfo VaapiH265Encoder::GetEncoderInfo() const
	{
		EncoderInfo info;
		info.implementation_name = implementationName;
		info.is_hardware_accelerated = true;
		info.supports_native_handle = false;
		info.supports_simulcast = false;
		info.scaling_settings = outputProcessor.GetScalingSettings();
		// Whole coding blocks, so that the coded picture is the frame.
		info.requested_resolution_alignment = 1 << log2MinCbSize;

		layers.FillFpsAllocation(info);

		return info;
	}
}
