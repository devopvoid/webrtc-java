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
		constexpr uint8_t kSliceTypeP = 1;
		constexpr uint8_t kSliceTypeI = 2;
		constexpr uint8_t kNalTrailR = 1;
		constexpr uint8_t kNalIdrWRadl = 19;

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
		initialized(false),
		codecSettings(),
		widthInCtbs(0),
		heightInCtbs(0),
		bitrateBps(0),
		framerate(30),
		ratesChanged(false),
		pictureOrder(0),
		current(0),
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
		VAConfigAttrib attributes[2] = {};
		attributes[0].type = VAConfigAttribEncHEVCBlockSizes;
		attributes[1].type = VAConfigAttribEncHEVCFeatures;

		if (library.Api().GetConfigAttributes(library.Display(), VAProfileHEVCMain, entrypoint, attributes, 2) !=
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
	}

	int32_t VaapiH265Encoder::InitEncode(const webrtc::VideoCodec * settings, const Settings & encoderSettings)
	{
		if (settings == nullptr || settings->width == 0 || settings->height == 0) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}
		if (settings->numberOfSimulcastStreams > 1) {
			return WEBRTC_VIDEO_CODEC_ERR_SIMULCAST_PARAMETERS_NOT_SUPPORTED;
		}

		// The coded picture is kept to whole coding blocks, so that it is the
		// frame and needs no cropping. WebRTC is asked for frames of that
		// alignment, but the first frames of a source can come before it
		// knows, so the encoder takes the aligned size below and drops the
		// rows and columns beyond it.
		const uint32_t alignment = 1u << log2MinCbSize;

		if (settings->width < alignment || settings->height < alignment) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}

		Release();

		const uint32_t ctbSize = 1u << log2CtbSize;

		codecSettings = *settings;
		codecSettings.width -= codecSettings.width % alignment;
		codecSettings.height -= codecSettings.height % alignment;
		widthInCtbs = (codecSettings.width + ctbSize - 1) / ctbSize;
		heightInCtbs = (codecSettings.height + ctbSize - 1) / ctbSize;
		bitrateBps = std::max(1u, codecSettings.startBitrate) * 1000;
		framerate = std::max(1u, codecSettings.maxFramerate);
		ratesChanged = true;
		pictureOrder = 0;
		current = 0;
		referenceValid = false;
		outputProcessor.Reset();

		// Whole coding tree blocks, which the driver may write all of.
		if (!session.Create(VAProfileHEVCMain, entrypoint, widthInCtbs << log2CtbSize, heightInCtbs << log2CtbSize)) {
			Release();
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		initialized = true;

		RTC_LOG(LS_INFO) << implementationName << " H.265 initialized: " << codecSettings.width << "x"
			<< codecSettings.height << " at " << bitrateBps << " bps, CTB " << ctbSize << ", min CB " << alignment;

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

		const int alignment = 1 << log2MinCbSize;
		std::vector<uint8_t> output;

		const bool encoded = session.Upload(frame, static_cast<int>(codecSettings.width),
			static_cast<int>(codecSettings.height), alignment - 1) && Submit(idr) && session.ReadOutput(output);

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
		if (!outputProcessor.Process(output, idr, image, info)) {
			RTC_LOG(LS_WARNING) << implementationName << " H.265: the driver wrote no parameter sets";
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		// The frame just encoded is the reference of the next one.
		referenceValid = true;
		current = 1 - current;
		pictureOrder++;
		ratesChanged = false;

		callback->OnEncodedImage(image, &info);

		return WEBRTC_VIDEO_CODEC_OK;
	}

	bool VaapiH265Encoder::Submit(bool idr)
	{
		if (idr) {
			VAEncSequenceParameterBufferHEVC sequence = {};
			FillSequence(sequence);

			if (!session.AddBuffer(VAEncSequenceParameterBufferType, &sequence, sizeof(sequence))) {
				return false;
			}
		}

		if ((idr || ratesChanged) && !session.AddRateControl(bitrateBps, framerate, kInitialQp)) {
			return false;
		}

		VAEncPictureParameterBufferHEVC picture = {};
		FillPicture(picture, idr);

		VAEncSliceParameterBufferHEVC slice = {};
		FillSlice(slice, idr);

		return session.AddBuffer(VAEncPictureParameterBufferType, &picture, sizeof(picture))
			&& session.AddBuffer(VAEncSliceParameterBufferType, &slice, sizeof(slice))
			&& session.Encode();
	}

	void VaapiH265Encoder::FillSequence(VAEncSequenceParameterBufferHEVC & sequence) const
	{
		// Main profile, main tier.
		sequence.general_profile_idc = 1;
		sequence.general_level_idc = LevelIdc(codecSettings.width * codecSettings.height, framerate);
		sequence.general_tier_flag = 0;
		sequence.intra_period = kIntraPeriod;
		sequence.intra_idr_period = kIntraPeriod;
		sequence.ip_period = 1;
		sequence.bits_per_second = bitrateBps;
		sequence.pic_width_in_luma_samples = static_cast<uint16_t>(codecSettings.width);
		sequence.pic_height_in_luma_samples = static_cast<uint16_t>(codecSettings.height);

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

	VAPictureHEVC VaapiH265Encoder::Reference() const
	{
		VAPictureHEVC reference = {};
		reference.picture_id = session.Reconstructed(1 - current);
		reference.pic_order_cnt = pictureOrder - 1;
		// A short-term reference before the current picture.
		reference.flags = VA_PICTURE_HEVC_RPS_ST_CURR_BEFORE;

		return reference;
	}

	void VaapiH265Encoder::FillPicture(VAEncPictureParameterBufferHEVC & picture, bool idr) const
	{
		picture.decoded_curr_pic.picture_id = session.Reconstructed(current);
		picture.decoded_curr_pic.pic_order_cnt = pictureOrder;
		picture.decoded_curr_pic.flags = 0;

		for (VAPictureHEVC & reference : picture.reference_frames) {
			InvalidatePicture(reference);
		}

		if (!idr) {
			picture.reference_frames[0] = Reference();
		}

		picture.coded_buf = session.CodedBuffer();
		// No collocated picture: there is no temporal motion vector
		// prediction.
		picture.collocated_ref_pic_index = 0xFF;
		picture.last_picture = 0;
		picture.pic_init_qp = kInitialQp;
		picture.diff_cu_qp_delta_depth = 0;
		picture.num_ref_idx_l0_default_active_minus1 = 0;
		picture.num_ref_idx_l1_default_active_minus1 = 0;
		picture.slice_pic_parameter_set_id = 0;
		picture.nal_unit_type = idr ? kNalIdrWRadl : kNalTrailR;

		picture.pic_fields.bits.idr_pic_flag = idr ? 1 : 0;
		picture.pic_fields.bits.coding_type = idr ? kCodingTypeI : kCodingTypeP;
		picture.pic_fields.bits.reference_pic_flag = 1;
		picture.pic_fields.bits.sign_data_hiding_enabled_flag = signDataHiding ? 1 : 0;
		picture.pic_fields.bits.transform_skip_enabled_flag = transformSkip ? 1 : 0;
		// Needed for the rate control to change the QP within a picture.
		picture.pic_fields.bits.cu_qp_delta_enabled_flag = cuQpDelta ? 1 : 0;
	}

	void VaapiH265Encoder::FillSlice(VAEncSliceParameterBufferHEVC & slice, bool idr) const
	{
		slice.slice_segment_address = 0;
		slice.num_ctu_in_slice = widthInCtbs * heightInCtbs;
		slice.slice_type = idr ? kSliceTypeI : kSliceTypeP;
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
			slice.ref_pic_list0[0] = Reference();
		}

		slice.max_num_merge_cand = 5;
		slice.slice_qp_delta = 0;

		slice.slice_fields.bits.last_slice_of_pic_flag = 1;
		slice.slice_fields.bits.slice_sao_luma_flag = sao ? 1 : 0;
		slice.slice_fields.bits.slice_sao_chroma_flag = sao ? 1 : 0;
	}

	void VaapiH265Encoder::SetRates(const RateControlParameters & parameters)
	{
		const uint32_t bitrate = parameters.bitrate.get_sum_bps();

		// A zero bitrate pauses the stream; the encoder gets no frames then.
		if (bitrate == 0) {
			return;
		}

		bitrateBps = bitrate;

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

		return info;
	}
}
