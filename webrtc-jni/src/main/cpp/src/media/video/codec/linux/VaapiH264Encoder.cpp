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

#include "media/video/codec/linux/VaapiH264Encoder.h"

#include "api/video/encoded_image.h"
#include "modules/video_coding/include/video_codec_interface.h"
#include "modules/video_coding/include/video_error_codes.h"
#include "rtc_base/logging.h"

#include <algorithm>

namespace jni
{
	namespace
	{
		// frame_num counts to 2^8 before it wraps.
		constexpr uint32_t kLog2MaxFrameNum = 8;
		constexpr uint32_t kMaxFrameNum = 1 << kLog2MaxFrameNum;

		// Key frames come when WebRTC asks for them; this only bounds how
		// long a stream may go without one if it never does.
		constexpr uint32_t kIntraPeriod = 3000;

		// H.264 slice types.
		constexpr uint8_t kSliceTypeP = 0;
		constexpr uint8_t kSliceTypeI = 2;

		// H.264 NAL unit types (table 7-1), and the nal_ref_idc of everything
		// the encoder writes that is used for reference.
		constexpr uint32_t kNalSlice = 1;
		constexpr uint32_t kNalIdrSlice = 5;
		constexpr uint32_t kNalSps = 7;
		constexpr uint32_t kNalPps = 8;
		constexpr uint32_t kNalRefIdc = 3;

		// Constrained Baseline: Baseline with constraint_set1_flag.
		constexpr uint32_t kProfileIdcBaseline = 66;

		// The packed headers the encoder writes where the driver takes both:
		// its slice headers go with its parameter sets only.
		constexpr uint32_t kPackedHeaders = VA_ENC_PACKED_HEADER_SEQUENCE | VA_ENC_PACKED_HEADER_SLICE;

		constexpr uint32_t kInitialQp = 26;

		// modification_of_pic_nums_idc: subtract from the predicted picture
		// number, or end the list of modifications.
		constexpr uint32_t kSubtractPicNum = 0;
		constexpr uint32_t kEndModifications = 3;

		// The lowest H.264 level whose limits a stream fits in, going by its
		// frame size and macroblock rate, per table A-1 of the standard.
		uint8_t LevelIdc(uint32_t frameSizeMbs, uint32_t framerate)
		{
			struct Level
			{
				uint8_t idc;
				uint32_t maxMbps;
				uint32_t maxFs;
			};

			static const Level levels[] = {
				{ 31, 108000, 3600 },
				{ 32, 216000, 5120 },
				{ 40, 245760, 8192 },
				{ 42, 522240, 8704 },
				{ 50, 589824, 22080 },
				{ 51, 983040, 36864 },
				{ 52, 2073600, 36864 }
			};

			const uint32_t mbps = frameSizeMbs * framerate;

			for (const Level & level : levels) {
				if (frameSizeMbs <= level.maxFs && mbps <= level.maxMbps) {
					return level.idc;
				}
			}

			return 52;
		}

		void InvalidatePicture(VAPictureH264 & picture)
		{
			picture = {};
			picture.picture_id = VA_INVALID_SURFACE;
			picture.flags = VA_PICTURE_H264_INVALID;
		}
	}

	VaapiH264Encoder::VaapiH264Encoder(VaapiLibrary & library, const webrtc::SdpVideoFormat & format) :
		library(library),
		implementationName("VA-API (" + library.Vendor() + ")"),
		session(library, "VA-API H.264"),
		packedHeaders(VA_ENC_PACKED_HEADER_NONE),
		initialized(false),
		codecSettings(),
		widthInMbs(0),
		heightInMbs(0),
		bitrateBps(0),
		framerate(30),
		ratesChanged(false),
		previousReferenceFrameNum(0),
		idrPicId(0),
		slotFrameNums{ 0, 0 },
		referenceValid(false),
		callback(nullptr),
		outputProcessor(webrtc::kVideoCodecH264, format)
	{
		VAConfigAttrib packed = {};
		packed.type = VAConfigAttribEncPackedHeaders;

		if (library.Api().GetConfigAttributes(library.Display(), VAProfileH264ConstrainedBaseline, library.Entrypoint(),
			&packed, 1) == VA_STATUS_SUCCESS && packed.value != VA_ATTRIB_NOT_SUPPORTED
			&& (packed.value & kPackedHeaders) == kPackedHeaders)
		{
			packedHeaders = kPackedHeaders;
		}
	}

	VaapiH264Encoder::~VaapiH264Encoder()
	{
		Release();
	}

	int32_t VaapiH264Encoder::InitEncode(const webrtc::VideoCodec * settings, const Settings & encoderSettings)
	{
		if (settings == nullptr || settings->width == 0 || settings->height == 0) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}
		if (settings->numberOfSimulcastStreams > 1) {
			return WEBRTC_VIDEO_CODEC_ERR_SIMULCAST_PARAMETERS_NOT_SUPPORTED;
		}
		// NV12 has chroma at half the resolution in both directions, and the
		// frame is cropped in units of two pixels. The first frame of a source
		// can be odd, since WebRTC asks the source for
		// requested_resolution_alignment only once the encoder has been set up.
		// Refusing it would leave the whole session to the software encoder, so
		// the encoder takes the even size below it and drops the last row and
		// column of such frames.
		if (settings->width < 2 || settings->height < 2) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}

		// Temporal layers, where the encoder writes the slice headers, which
		// carry what makes the layers; the rest is left to OpenH264.
		const webrtc::ScalabilityMode mode = settings->GetScalabilityMode().value_or(webrtc::ScalabilityMode::kL1T1);

		if (mode != webrtc::ScalabilityMode::kL1T1 && (packedHeaders != kPackedHeaders
			|| (mode != webrtc::ScalabilityMode::kL1T2 && mode != webrtc::ScalabilityMode::kL1T3)))
		{
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}

		Release();

		codecSettings = *settings;
		codecSettings.width &= ~1;
		codecSettings.height &= ~1;
		widthInMbs = (codecSettings.width + 15) / 16;
		heightInMbs = (codecSettings.height + 15) / 16;
		bitrateBps = std::max(1u, codecSettings.startBitrate) * 1000;
		framerate = std::max(1u, codecSettings.maxFramerate);
		ratesChanged = true;
		previousReferenceFrameNum = 0;
		referenceValid = false;
		outputProcessor.Reset();
		// One rate control for all layers, which then treats them alike:
		// Intel's driver has none of each layer for H.264, and AMD's, which
		// says it has, overshoots the bitrate by half again with it.
		layers.Configure(mode, bitrateBps, false);

		if (!outputProcessor.SetScalabilityMode(mode)) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}

		// Whole macroblocks; the stream is cropped to the frame size.
		const uint32_t width = widthInMbs * 16;
		const uint32_t height = heightInMbs * 16;

		if (!session.Create(VAProfileH264ConstrainedBaseline, library.Entrypoint(), width, height, width, height,
			packedHeaders, VaapiTemporalLayers::kReconstructedSurfaces))
		{
			Release();
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		initialized = true;

		RTC_LOG(LS_INFO) << implementationName << " initialized: " << codecSettings.width << "x"
			<< codecSettings.height << " at " << bitrateBps << " bps, " << webrtc::ScalabilityModeToString(mode);

		return WEBRTC_VIDEO_CODEC_OK;
	}

	int32_t VaapiH264Encoder::RegisterEncodeCompleteCallback(webrtc::EncodedImageCallback * encodeCallback)
	{
		callback = encodeCallback;

		return WEBRTC_VIDEO_CODEC_OK;
	}

	int32_t VaapiH264Encoder::Release()
	{
		session.Destroy();

		initialized = false;
		referenceValid = false;

		return WEBRTC_VIDEO_CODEC_OK;
	}

	int32_t VaapiH264Encoder::Encode(const webrtc::VideoFrame & frame,
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

		const auto layerFrame = outputProcessor.NextFrameConfig(idr);

		// No layer is encoded now, as when WebRTC gives none a bitrate.
		if (!layerFrame) {
			return WEBRTC_VIDEO_CODEC_OK;
		}

		const auto layerPlan = layers.Plan(idr, *layerFrame);

		if (!layerPlan) {
			RTC_LOG(LS_WARNING) << implementationName << " H.264: a frame refers to a slot that holds none";
			return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
		}

		// frame_num goes up after each reference picture only, so that a
		// receiver misses no frame_num for a picture of the top layer it goes
		// without.
		Picture picture;
		picture.frame = *layerPlan;
		picture.reference = idr || layerPlan->refreshFlags != 0;
		picture.frameNum = idr ? 0 : (previousReferenceFrameNum + 1) % kMaxFrameNum;

		std::vector<uint8_t> output;

		// WebRTC initializes the encoder again when the frame size changes,
		// so a frame of another size is one that raced with that. An odd frame
		// is cropped to the even size the encoder took, which needs no copy.
		const bool encoded = session.Upload(frame, static_cast<int>(codecSettings.width),
			static_cast<int>(codecSettings.height), 1) && Submit(idr, picture) && session.ReadOutput(output);

		session.DropBuffers();


		if (!encoded) {
			return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
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
		// stream nothing can decode; better in software then.
		if (!outputProcessor.Process(output, idr, *layerFrame, image, info)) {
			return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
		}

		// The slots the frame replaces hold it now.
		layers.Commit(*layerPlan);

		for (int slot = 0; slot < layers.SlotCount(); slot++) {
			if (layerPlan->keptFlags & (1 << slot)) {
				slotFrameNums[slot] = picture.frameNum;
			}
		}

		if (picture.reference) {
			previousReferenceFrameNum = picture.frameNum;
		}

		referenceValid = true;
		ratesChanged = false;

		if (idr) {
			idrPicId = (idrPicId + 1) & 0xFFFF;
		}

		callback->OnEncodedImage(image, &info);

		return WEBRTC_VIDEO_CODEC_OK;
	}

	bool VaapiH264Encoder::Submit(bool idr, const Picture & picture)
	{
		if (idr) {
			VAEncSequenceParameterBufferH264 sequence = {};
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

		VAEncPictureParameterBufferH264 parameters = {};
		FillPicture(parameters, idr, picture);

		VAEncSliceParameterBufferH264 slice = {};
		FillSlice(slice, idr, picture);

		if (!session.AddBuffer(VAEncPictureParameterBufferType, &parameters, sizeof(parameters))) {
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
			const size_t bits = WriteSliceHeader(sliceHeader, idr, picture);

			if (!session.AddPackedHeader(VAEncPackedHeaderSlice, sliceHeader, bits)) {
				return false;
			}
		}

		return session.AddBuffer(VAEncSliceParameterBufferType, &slice, sizeof(slice)) && session.Encode();
	}

	void VaapiH264Encoder::FillSequence(VAEncSequenceParameterBufferH264 & sequence) const
	{
		sequence.seq_parameter_set_id = 0;
		sequence.level_idc = LevelIdc(widthInMbs * heightInMbs, framerate);
		sequence.intra_period = kIntraPeriod;
		sequence.intra_idr_period = kIntraPeriod;
		sequence.ip_period = 1;
		sequence.bits_per_second = bitrateBps;
		// The pictures of both reference slots with layers.
		sequence.max_num_ref_frames = layers.Count() > 1 ? 2 : 1;
		sequence.picture_width_in_mbs = static_cast<uint16_t>(widthInMbs);
		sequence.picture_height_in_mbs = static_cast<uint16_t>(heightInMbs);

		sequence.seq_fields.bits.chroma_format_idc = 1;
		sequence.seq_fields.bits.frame_mbs_only_flag = 1;
		sequence.seq_fields.bits.direct_8x8_inference_flag = 1;
		sequence.seq_fields.bits.log2_max_frame_num_minus4 = kLog2MaxFrameNum - 4;
		// Display order follows frame_num, which there are no B-frames to
		// break.
		sequence.seq_fields.bits.pic_order_cnt_type = 2;

		const uint32_t croppedRight = widthInMbs * 16 - codecSettings.width;
		const uint32_t croppedBottom = heightInMbs * 16 - codecSettings.height;

		if (croppedRight > 0 || croppedBottom > 0) {
			// In units of two luma samples for 4:2:0 frames.
			sequence.frame_cropping_flag = 1;
			sequence.frame_crop_right_offset = croppedRight / 2;
			sequence.frame_crop_bottom_offset = croppedBottom / 2;
		}

		sequence.vui_parameters_present_flag = 1;
		sequence.vui_fields.bits.timing_info_present_flag = 1;
		sequence.num_units_in_tick = 1;
		sequence.time_scale = framerate * 2;
	}

	int32_t VaapiH264Encoder::OrderCount(uint32_t frameNum, bool reference)
	{
		// Twice frame_num for a reference picture, one less for another.
		return static_cast<int32_t>(frameNum * 2) - (reference ? 0 : 1);
	}

	VAPictureH264 VaapiH264Encoder::Reference(int slot) const
	{
		VAPictureH264 reference = {};
		reference.picture_id = session.Reconstructed(layers.Surface(slot));
		reference.frame_idx = slotFrameNums[slot];
		reference.flags = VA_PICTURE_H264_SHORT_TERM_REFERENCE;
		reference.TopFieldOrderCnt = OrderCount(slotFrameNums[slot], true);
		reference.BottomFieldOrderCnt = reference.TopFieldOrderCnt;

		return reference;
	}

	void VaapiH264Encoder::FillPicture(VAEncPictureParameterBufferH264 & parameters, bool idr,
		const Picture & picture) const
	{
		parameters.CurrPic.picture_id = session.Reconstructed(picture.frame.surface);
		parameters.CurrPic.frame_idx = picture.frameNum;
		parameters.CurrPic.flags = 0;
		parameters.CurrPic.TopFieldOrderCnt = OrderCount(picture.frameNum, picture.reference);
		parameters.CurrPic.BottomFieldOrderCnt = parameters.CurrPic.TopFieldOrderCnt;

		for (VAPictureH264 & reference : parameters.ReferenceFrames) {
			InvalidatePicture(reference);
		}

		if (!idr) {
			// The pictures the decoder keeps: the one referred to, and with
			// layers the other slot's too.
			int count = 0;

			for (int slot = 0; slot < layers.SlotCount(); slot++) {
				const bool referred = slot == picture.frame.references[0];
				const bool kept = layers.Count() > 1 && layers.Surface(slot) >= 0;
				bool listed = false;

				for (int i = 0; i < count; i++) {
					listed |= parameters.ReferenceFrames[i].picture_id == session.Reconstructed(layers.Surface(slot));
				}

				if ((referred || kept) && !listed) {
					parameters.ReferenceFrames[count++] = Reference(slot);
				}
			}
		}

		parameters.coded_buf = session.CodedBuffer();
		parameters.pic_parameter_set_id = 0;
		parameters.seq_parameter_set_id = 0;
		parameters.last_picture = 0;
		parameters.frame_num = static_cast<uint16_t>(picture.frameNum);
		parameters.pic_init_qp = kInitialQp;
		parameters.num_ref_idx_l0_active_minus1 = 0;
		parameters.num_ref_idx_l1_active_minus1 = 0;

		parameters.pic_fields.bits.idr_pic_flag = idr ? 1 : 0;
		parameters.pic_fields.bits.reference_pic_flag = picture.reference ? 1 : 0;
		// CAVLC: Constrained Baseline has no CABAC.
		parameters.pic_fields.bits.entropy_coding_mode_flag = 0;
		parameters.pic_fields.bits.transform_8x8_mode_flag = 0;
		parameters.pic_fields.bits.deblocking_filter_control_present_flag = 1;
	}

	void VaapiH264Encoder::FillSlice(VAEncSliceParameterBufferH264 & slice, bool idr, const Picture & picture) const
	{
		slice.macroblock_address = 0;
		slice.num_macroblocks = widthInMbs * heightInMbs;
		slice.macroblock_info = VA_INVALID_ID;
		slice.slice_type = idr ? kSliceTypeI : kSliceTypeP;
		slice.pic_parameter_set_id = 0;
		slice.idr_pic_id = static_cast<uint16_t>(idrPicId);
		slice.pic_order_cnt_lsb = 0;
		slice.num_ref_idx_active_override_flag = 0;
		slice.num_ref_idx_l0_active_minus1 = 0;

		for (VAPictureH264 & reference : slice.RefPicList0) {
			InvalidatePicture(reference);
		}
		for (VAPictureH264 & reference : slice.RefPicList1) {
			InvalidatePicture(reference);
		}

		if (!idr) {
			slice.RefPicList0[0] = Reference(picture.frame.references[0]);
		}

		slice.slice_qp_delta = 0;
		slice.disable_deblocking_filter_idc = 0;
	}

	size_t VaapiH264Encoder::WriteParameterSets(std::vector<uint8_t> & output) const
	{
		const bool layered = layers.Count() > 1;

		// The SPS (7.3.2.1.1) of FillSequence().
		NalUnitWriter sps;
		sps.Bits(0, 1);
		sps.Bits(kNalRefIdc, 2);
		sps.Bits(kNalSps, 5);

		sps.Bits(kProfileIdcBaseline, 8);
		// constraint_set0_flag and constraint_set1_flag, the other four and
		// reserved_zero_2bits not.
		sps.Bits(0b11000000, 8);
		sps.Bits(LevelIdc(widthInMbs * heightInMbs, framerate), 8);
		sps.Ue(0);
		sps.Ue(kLog2MaxFrameNum - 4);
		sps.Ue(2);
		// max_num_ref_frames, and gaps_in_frame_num_value_allowed_flag, which
		// a receiver without the upper layers needs.
		sps.Ue(layered ? 2 : 1);
		sps.Flag(layered);
		sps.Ue(widthInMbs - 1);
		sps.Ue(heightInMbs - 1);
		sps.Flag(true);
		sps.Flag(true);

		const uint32_t croppedRight = widthInMbs * 16 - codecSettings.width;
		const uint32_t croppedBottom = heightInMbs * 16 - codecSettings.height;
		const bool cropped = croppedRight > 0 || croppedBottom > 0;

		sps.Flag(cropped);

		if (cropped) {
			sps.Ue(0);
			sps.Ue(croppedRight / 2);
			sps.Ue(0);
			sps.Ue(croppedBottom / 2);
		}

		// VUI (E.1.1) with the timing only.
		sps.Flag(true);
		sps.Flag(false);
		sps.Flag(false);
		sps.Flag(false);
		sps.Flag(false);
		sps.Flag(true);
		sps.Bits(1, 32);
		sps.Bits(framerate * 2, 32);
		sps.Flag(false);
		sps.Flag(false);
		sps.Flag(false);
		sps.Flag(false);
		sps.Flag(false);
		sps.AlignWithStopBit();

		// The PPS (7.3.2.2) of FillPicture().
		NalUnitWriter pps;
		pps.Bits(0, 1);
		pps.Bits(kNalRefIdc, 2);
		pps.Bits(kNalPps, 5);

		pps.Ue(0);
		pps.Ue(0);
		// CAVLC.
		pps.Flag(false);
		pps.Flag(false);
		pps.Ue(0);
		pps.Ue(0);
		pps.Ue(0);
		pps.Flag(false);
		pps.Bits(0, 2);
		pps.Se(static_cast<int32_t>(kInitialQp) - 26);
		pps.Se(0);
		pps.Se(0);
		// deblocking_filter_control_present_flag.
		pps.Flag(true);
		pps.Flag(false);
		pps.Flag(false);
		pps.AlignWithStopBit();

		return sps.AppendTo(output) + pps.AppendTo(output);
	}

	size_t VaapiH264Encoder::WriteSliceHeader(std::vector<uint8_t> & output, bool idr, const Picture & picture) const
	{
		// The slice header (7.3.3) of FillSlice(), which the driver goes on
		// from with the slice data.
		NalUnitWriter header;
		header.Bits(0, 1);
		header.Bits(picture.reference ? kNalRefIdc : 0, 2);
		header.Bits(idr ? kNalIdrSlice : kNalSlice, 5);

		header.Ue(0);
		header.Ue(idr ? kSliceTypeI : kSliceTypeP);
		header.Ue(0);
		header.Bits(picture.frameNum, kLog2MaxFrameNum);

		if (idr) {
			header.Ue(idrPicId);
		}
		else {
			// num_ref_idx_active_override_flag.
			header.Flag(false);

			// ref_pic_list_modification(): with layers, the picture referred
			// to goes first in the list, which would otherwise start with the
			// newest reference picture.
			const bool layered = layers.Count() > 1;

			header.Flag(layered);

			if (layered) {
				const uint32_t referenceFrameNum = slotFrameNums[picture.frame.references[0]];
				const uint32_t difference = (picture.frameNum + kMaxFrameNum - referenceFrameNum) % kMaxFrameNum;

				header.Ue(kSubtractPicNum);
				header.Ue(difference - 1);
				header.Ue(kEndModifications);
			}
		}

		// dec_ref_pic_marking() of a reference picture: no_output_of_prior_pics_flag
		// and long_term_reference_flag, or adaptive_ref_pic_marking_mode_flag.
		if (picture.reference) {
			if (idr) {
				header.Flag(false);
				header.Flag(false);
			}
			else {
				header.Flag(false);
			}
		}

		header.Se(0);
		// disable_deblocking_filter_idc, slice_alpha_c0_offset_div2 and
		// slice_beta_offset_div2.
		header.Ue(0);
		header.Se(0);
		header.Se(0);

		return header.AppendTo(output);
	}

	void VaapiH264Encoder::SetRates(const RateControlParameters & parameters)
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

	webrtc::VideoEncoder::EncoderInfo VaapiH264Encoder::GetEncoderInfo() const
	{
		EncoderInfo info;
		info.implementation_name = implementationName;
		info.is_hardware_accelerated = true;
		info.supports_native_handle = false;
		info.supports_simulcast = false;
		info.scaling_settings = outputProcessor.GetScalingSettings();
		// NV12 needs even dimensions.
		info.requested_resolution_alignment = 2;

		layers.FillFpsAllocation(info);

		return info;
	}
}
