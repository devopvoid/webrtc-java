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

#include "media/video/codec/linux/VaapiH264Decoder.h"

#include "api/video/video_frame.h"
#include "common_video/h264/h264_common.h"
#include "modules/video_coding/include/video_error_codes.h"
#include "rtc_base/logging.h"

#include <algorithm>

namespace jni
{
	namespace
	{
		constexpr size_t kMaxReferenceFrames = 16;
		constexpr size_t kMaxListEntries = 32;

		// Data partitions (table 7-1), which the decoder does not take.
		constexpr uint8_t kNalPartitionA = 2;
		constexpr uint8_t kNalPartitionC = 4;

		// The raster positions of the coefficients in zigzag order (8.5.6).
		constexpr uint8_t kZigzag4x4[16] = { 0, 1, 4, 8, 5, 2, 3, 6, 9, 12, 13, 10, 7, 11, 14, 15 };
		constexpr uint8_t kZigzag8x8[64] = {
			0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
			12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
			35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
			58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63
		};

		void InvalidatePicture(VAPictureH264 & picture)
		{
			picture = {};
			picture.picture_id = VA_INVALID_SURFACE;
			picture.flags = VA_PICTURE_H264_INVALID;
		}
	}

	int32_t VaapiH264Decoder::Picture::Poc() const
	{
		return std::min(topPoc, bottomPoc);
	}

	bool VaapiH264Decoder::Picture::IsReference() const
	{
		return shortTerm || longTerm;
	}

	VaapiH264Decoder::VaapiH264Decoder(VaapiLibrary & library) :
		library(library),
		implementationName("VA-API (" + library.Vendor() + ")"),
		callback(nullptr),
		session(library, implementationName + " H.264"),
		widthInMbs(0),
		heightInMbs(0),
		maxNumRefFrames(0),
		maxNumReorder(0),
		cropX(0),
		cropY(0),
		width(0),
		height(0),
		maxLongTermFrameIdxPlus1(0),
		prevPocMsb(0),
		prevPocLsb(0),
		prevFrameNum(0),
		prevFrameNumOffset(0),
		prevRefFrameNum(0),
		frameNumOffset(0),
		pocMsb(0),
		mmco5(false),
		started(false),
		failed(false)
	{
	}

	VaapiH264Decoder::~VaapiH264Decoder()
	{
		Release();
	}

	bool VaapiH264Decoder::Configure(const Settings & settings)
	{
		if (settings.codec_type() != webrtc::kVideoCodecH264) {
			return false;
		}

		Release();

		RTC_LOG(LS_INFO) << implementationName << " H.264 decoder configured";

		return true;
	}

	int32_t VaapiH264Decoder::Decode(const webrtc::EncodedImage & image, int64_t renderTimeMs)
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
		std::vector<Slice> slices;

		const auto decode = [&]() {
			const Result result = DecodePicture(slices, image, renderTimeMs);

			slices.clear();

			if (result == Result::kMissingReference) {
				RTC_LOG(LS_INFO) << implementationName << " H.264: a picture refers to one that is not there; "
					"waiting for a key frame";

				dpb.clear();
				started = false;

				return WEBRTC_VIDEO_CODEC_ERROR;
			}
			if (result == Result::kFailed) {
				failed = true;

				return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
			}

			return WEBRTC_VIDEO_CODEC_OK;
		};

		for (const webrtc::H264::NaluIndex & index : webrtc::H264::FindNaluIndices(data)) {
			const std::span<const uint8_t> nal = data.subspan(index.payload_start_offset, index.payload_size);

			if (nal.empty()) {
				continue;
			}

			const uint8_t type = nal[0] & 0x1F;

			if (type == H264Parser::kNalSps || type == H264Parser::kNalPps) {
				// The parameter sets of the next picture.
				if (!slices.empty()) {
					if (const int32_t result = decode(); result != WEBRTC_VIDEO_CODEC_OK) {
						return result;
					}
				}

				const bool parsed = type == H264Parser::kNalSps ? parser.ParseSps(nal) : parser.ParsePps(nal);

				if (!parsed) {
					RTC_LOG(LS_INFO) << implementationName << " H.264: a parameter set of a stream that is not "
						"progressive 8 bit 4:2:0 without slice groups; decoding it in software";
					failed = true;

					return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
				}

				continue;
			}

			if (type == H264Parser::kNalEndOfSequence) {
				if (!slices.empty()) {
					if (const int32_t result = decode(); result != WEBRTC_VIDEO_CODEC_OK) {
						return result;
					}
				}

				started = false;
				continue;
			}

			if (type >= kNalPartitionA && type <= kNalPartitionC) {
				RTC_LOG(LS_INFO) << implementationName << " H.264: data partitions; decoding them in software";
				failed = true;

				return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
			}

			if (type != H264Parser::kNalSlice && type != H264Parser::kNalIdr) {
				continue;
			}

			std::optional<H264SliceHeader> header = parser.ParseSliceHeader(nal);

			if (!header) {
				RTC_LOG(LS_INFO) << implementationName << " H.264: a slice header that is broken, of an SP or SI "
					"slice, or refers to a parameter set that is not there";
				failed = true;

				return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
			}

			// Redundant pictures are for decoders that lost the primary one.
			if (header->redundantPicCnt > 0) {
				continue;
			}

			if (header->firstMbInSlice == 0 && !slices.empty()) {
				if (const int32_t result = decode(); result != WEBRTC_VIDEO_CODEC_OK) {
					return result;
				}
			}

			slices.push_back(Slice { *header, nal });
		}

		if (slices.empty()) {
			return WEBRTC_VIDEO_CODEC_OK;
		}

		return decode();
	}

	int32_t VaapiH264Decoder::RegisterDecodeCompleteCallback(webrtc::DecodedImageCallback * decodeCallback)
	{
		callback = decodeCallback;

		return WEBRTC_VIDEO_CODEC_OK;
	}

	int32_t VaapiH264Decoder::Release()
	{
		session.Destroy();
		dpb.clear();

		parser.Reset();
		widthInMbs = 0;
		heightInMbs = 0;
		maxLongTermFrameIdxPlus1 = 0;
		started = false;
		failed = false;

		return WEBRTC_VIDEO_CODEC_OK;
	}

	webrtc::VideoDecoder::DecoderInfo VaapiH264Decoder::GetDecoderInfo() const
	{
		DecoderInfo info;
		info.implementation_name = implementationName;
		info.is_hardware_accelerated = true;

		return info;
	}

	const char * VaapiH264Decoder::ImplementationName() const
	{
		return implementationName.c_str();
	}

	VaapiH264Decoder::Result VaapiH264Decoder::DecodePicture(const std::vector<Slice> & slices,
		const webrtc::EncodedImage & image, int64_t renderTimeMs)
	{
		const H264SliceHeader & first = slices.front().header;

		if (first.firstMbInSlice != 0) {
			return Result::kMissingReference;
		}

		const H264Pps * pps = parser.Pps(first.ppsId);
		const H264Sps * sps = pps != nullptr ? parser.Sps(pps->spsId) : nullptr;

		if (sps == nullptr) {
			return Result::kFailed;
		}

		const bool idr = first.IsIdr();
		const uint32_t maxFrameNum = sps->MaxFrameNum();

		if (!idr && !started) {
			return Result::kMissingReference;
		}

		if (idr) {
			// The frames of the sequence before are output, unless the picture
			// says not to, and all references are let go.
			if (first.noOutputOfPriorPics) {
				dpb.clear();
			}
			else if (!Bump(0)) {
				return Result::kFailed;
			}

			dpb.clear();

			const uint32_t spsMaxNumReorder = sps->maxNumReorderFrames.value_or(0);

			if (!session.IsCreated() || sps->widthInMbs != widthInMbs || sps->heightInMbs != heightInMbs
				|| sps->maxNumRefFrames != maxNumRefFrames || spsMaxNumReorder != maxNumReorder)
			{
				// The reference frames, the frames waiting for output, the
				// one being decoded and one more.
				const uint32_t count = std::max(sps->maxNumRefFrames, 1u) + spsMaxNumReorder + 2;

				if (!session.Create(VAProfileH264High, sps->widthInMbs * 16, sps->heightInMbs * 16, count)) {
					return Result::kFailed;
				}

				widthInMbs = sps->widthInMbs;
				heightInMbs = sps->heightInMbs;
				maxNumRefFrames = sps->maxNumRefFrames;
				maxNumReorder = spsMaxNumReorder;

				RTC_LOG(LS_INFO) << implementationName << " H.264 decoding " << widthInMbs * 16 - sps->cropLeft
					- sps->cropRight << "x" << heightInMbs * 16 - sps->cropTop - sps->cropBottom;
			}
		}
		else {
			if (sps->widthInMbs != widthInMbs || sps->heightInMbs != heightInMbs) {
				RTC_LOG(LS_INFO) << implementationName << " H.264: the size changes without a key frame";
				return Result::kFailed;
			}

			// A frame that is lost leaves a gap in frame_num.
			if (first.frameNum != prevRefFrameNum && first.frameNum != (prevRefFrameNum + 1) % maxFrameNum) {
				return Result::kMissingReference;
			}
		}

		cropX = sps->cropLeft;
		cropY = sps->cropTop;
		width = widthInMbs * 16 - sps->cropLeft - sps->cropRight;
		height = heightInMbs * 16 - sps->cropTop - sps->cropBottom;

		Picture current;
		current.frameNum = first.frameNum;
		PictureOrderCount(first, *sps, current.topPoc, current.bottomPoc);

		// A full buffer makes room, in output order.
		RemoveUnused();

		while (static_cast<int>(dpb.size()) + 1 >= session.SurfaceCount()) {
			const uint32_t waiting = static_cast<uint32_t>(std::count_if(dpb.begin(), dpb.end(),
				[](const Picture & picture) {
					return picture.waitingOutput;
				}));

			if (waiting == 0) {
				break;
			}
			if (!Bump(waiting - 1)) {
				return Result::kFailed;
			}
		}

		current.surface = FreeSurface();

		if (current.surface < 0) {
			RTC_LOG(LS_WARNING) << implementationName << " H.264: no surface is free";
			return Result::kFailed;
		}

		VAPictureParameterBufferH264 picture = {};
		picture.CurrPic.picture_id = session.Surface(current.surface);
		picture.CurrPic.frame_idx = first.frameNum;
		picture.CurrPic.flags = 0;
		picture.CurrPic.TopFieldOrderCnt = current.topPoc;
		picture.CurrPic.BottomFieldOrderCnt = current.bottomPoc;

		for (VAPictureH264 & reference : picture.ReferenceFrames) {
			InvalidatePicture(reference);
		}

		size_t count = 0;

		for (const Picture & reference : dpb) {
			if (reference.IsReference() && count < kMaxReferenceFrames) {
				picture.ReferenceFrames[count++] = ToVaPicture(reference);
			}
		}

		picture.picture_width_in_mbs_minus1 = static_cast<uint16_t>(sps->widthInMbs - 1);
		picture.picture_height_in_mbs_minus1 = static_cast<uint16_t>(sps->heightInMbs - 1);
		picture.bit_depth_luma_minus8 = static_cast<uint8_t>(sps->bitDepthLumaMinus8);
		picture.bit_depth_chroma_minus8 = static_cast<uint8_t>(sps->bitDepthChromaMinus8);
		picture.num_ref_frames = static_cast<uint8_t>(sps->maxNumRefFrames);

		auto & seq = picture.seq_fields.bits;
		seq.chroma_format_idc = sps->chromaFormatIdc;
		seq.gaps_in_frame_num_value_allowed_flag = sps->gapsInFrameNumAllowed ? 1 : 0;
		seq.frame_mbs_only_flag = 1;
		seq.mb_adaptive_frame_field_flag = 0;
		seq.direct_8x8_inference_flag = sps->direct8x8Inference ? 1 : 0;
		// Bi-prediction of blocks smaller than 8x8 is left out from level 3.1
		// on (A.3.3.2).
		seq.MinLumaBiPredSize8x8 = sps->levelIdc >= 31 ? 1 : 0;
		seq.log2_max_frame_num_minus4 = sps->log2MaxFrameNumMinus4;
		seq.pic_order_cnt_type = sps->pocType;
		seq.log2_max_pic_order_cnt_lsb_minus4 = sps->log2MaxPocLsbMinus4;
		seq.delta_pic_order_always_zero_flag = sps->deltaPicOrderAlwaysZero ? 1 : 0;

		picture.pic_init_qp_minus26 = static_cast<int8_t>(pps->picInitQpMinus26);
		picture.pic_init_qs_minus26 = static_cast<int8_t>(pps->picInitQsMinus26);
		picture.chroma_qp_index_offset = static_cast<int8_t>(pps->chromaQpIndexOffset);
		picture.second_chroma_qp_index_offset = static_cast<int8_t>(pps->secondChromaQpIndexOffset);

		auto & fields = picture.pic_fields.bits;
		fields.entropy_coding_mode_flag = pps->entropyCodingMode ? 1 : 0;
		fields.weighted_pred_flag = pps->weightedPred ? 1 : 0;
		fields.weighted_bipred_idc = pps->weightedBipredIdc;
		fields.transform_8x8_mode_flag = pps->transform8x8Mode ? 1 : 0;
		fields.field_pic_flag = 0;
		fields.constrained_intra_pred_flag = pps->constrainedIntraPred ? 1 : 0;
		fields.pic_order_present_flag = pps->bottomFieldPicOrderInFramePresent ? 1 : 0;
		fields.deblocking_filter_control_present_flag = pps->deblockingFilterControlPresent ? 1 : 0;
		fields.redundant_pic_cnt_present_flag = pps->redundantPicCntPresent ? 1 : 0;
		fields.reference_pic_flag = first.nalRefIdc != 0 ? 1 : 0;

		picture.frame_num = static_cast<uint16_t>(first.frameNum);

		const H264ScalingLists lists = H264Parser::ScalingLists(*sps, *pps);
		VAIQMatrixBufferH264 matrix = {};

		// VA-API takes the lists in raster order, as the coefficients of a
		// block are laid out; they are coded in zigzag order.
		for (int i = 0; i < 6; i++) {
			for (int j = 0; j < 16; j++) {
				matrix.ScalingList4x4[i][kZigzag4x4[j]] = lists.list4x4[i][j];
			}
		}
		for (int i = 0; i < 2; i++) {
			for (int j = 0; j < 64; j++) {
				matrix.ScalingList8x8[i][kZigzag8x8[j]] = lists.list8x8[i][j];
			}
		}

		std::vector<VASliceParameterBufferH264> sliceParameters(slices.size());

		for (size_t i = 0; i < slices.size(); i++) {
			const H264SliceHeader & header = slices[i].header;
			VASliceParameterBufferH264 & parameters = sliceParameters[i];
			std::vector<int> referenceLists[2];

			if (!BuildReferenceLists(header, *sps, current.Poc(), referenceLists[0], referenceLists[1])) {
				return Result::kMissingReference;
			}

			parameters.slice_data_size = static_cast<uint32_t>(slices[i].nal.size());
			parameters.slice_data_offset = 0;
			parameters.slice_data_flag = VA_SLICE_DATA_FLAG_ALL;
			parameters.slice_data_bit_offset = static_cast<uint16_t>(header.dataBitOffset);
			parameters.first_mb_in_slice = static_cast<uint16_t>(header.firstMbInSlice);
			parameters.slice_type = static_cast<uint8_t>(header.sliceType);
			parameters.direct_spatial_mv_pred_flag = header.directSpatialMvPred ? 1 : 0;
			parameters.num_ref_idx_l0_active_minus1 = header.IsIntra() ? 0 : static_cast<uint8_t>(header.numRefIdxL0Minus1);
			parameters.num_ref_idx_l1_active_minus1 = header.IsB() ? static_cast<uint8_t>(header.numRefIdxL1Minus1) : 0;
			parameters.cabac_init_idc = static_cast<uint8_t>(header.cabacInitIdc);
			parameters.slice_qp_delta = static_cast<int8_t>(header.qpDelta);
			parameters.disable_deblocking_filter_idc = static_cast<uint8_t>(header.disableDeblockingFilterIdc);
			parameters.slice_alpha_c0_offset_div2 = static_cast<int8_t>(header.alphaOffsetDiv2);
			parameters.slice_beta_offset_div2 = static_cast<int8_t>(header.betaOffsetDiv2);

			VAPictureH264 * vaLists[2] = { parameters.RefPicList0, parameters.RefPicList1 };

			for (int list = 0; list < 2; list++) {
				for (size_t j = 0; j < kMaxListEntries; j++) {
					InvalidatePicture(vaLists[list][j]);
				}

				for (size_t j = 0; j < referenceLists[list].size() && j < kMaxListEntries; j++) {
					const Picture * reference = FindBySurface(referenceLists[list][j]);

					if (reference != nullptr) {
						vaLists[list][j] = ToVaPicture(*reference);
					}
				}
			}

			parameters.luma_log2_weight_denom = static_cast<uint8_t>(header.lumaLog2WeightDenom);
			parameters.chroma_log2_weight_denom = static_cast<uint8_t>(header.chromaLog2WeightDenom);
			parameters.luma_weight_l0_flag = header.lumaWeightsGiven[0] ? 1 : 0;
			parameters.chroma_weight_l0_flag = header.chromaWeightsGiven[0] ? 1 : 0;
			parameters.luma_weight_l1_flag = header.lumaWeightsGiven[1] ? 1 : 0;
			parameters.chroma_weight_l1_flag = header.chromaWeightsGiven[1] ? 1 : 0;

			for (size_t j = 0; j < kMaxListEntries; j++) {
				parameters.luma_weight_l0[j] = static_cast<int16_t>(header.lumaWeight[0][j]);
				parameters.luma_offset_l0[j] = static_cast<int16_t>(header.lumaOffset[0][j]);
				parameters.luma_weight_l1[j] = static_cast<int16_t>(header.lumaWeight[1][j]);
				parameters.luma_offset_l1[j] = static_cast<int16_t>(header.lumaOffset[1][j]);

				for (int k = 0; k < 2; k++) {
					parameters.chroma_weight_l0[j][k] = static_cast<int16_t>(header.chromaWeight[0][j][k]);
					parameters.chroma_offset_l0[j][k] = static_cast<int16_t>(header.chromaOffset[0][j][k]);
					parameters.chroma_weight_l1[j][k] = static_cast<int16_t>(header.chromaWeight[1][j][k]);
					parameters.chroma_offset_l1[j][k] = static_cast<int16_t>(header.chromaOffset[1][j][k]);
				}
			}
		}

		std::vector<VaapiDecodeSession::Buffer> buffers = {
			{ VAPictureParameterBufferType, &picture, sizeof(picture) },
			{ VAIQMatrixBufferType, &matrix, sizeof(matrix) }
		};

		for (size_t i = 0; i < slices.size(); i++) {
			buffers.push_back({ VASliceParameterBufferType, &sliceParameters[i], sizeof(sliceParameters[i]) });
			buffers.push_back({ VASliceDataBufferType, slices[i].nal.data(), slices[i].nal.size() });
		}

		if (!session.Decode(current.surface, buffers)) {
			return Result::kFailed;
		}

		current.waitingOutput = true;
		current.rtpTimestamp = image.RtpTimestamp();
		current.ntpTimeMs = image.ntp_time_ms_;
		current.renderTimeMs = renderTimeMs;
		current.rotation = image.rotation_;

		mmco5 = false;

		if (first.nalRefIdc != 0) {
			const Result marked = MarkReferences(first, *sps, current);

			if (marked != Result::kOk) {
				return marked;
			}
		}

		// The state the next picture's order count and frame_num derive from;
		// operation 5 starts them over, as an IDR picture does.
		prevFrameNum = mmco5 ? 0 : first.frameNum;
		prevFrameNumOffset = mmco5 ? 0 : frameNumOffset;

		if (first.nalRefIdc != 0) {
			prevRefFrameNum = mmco5 ? 0 : first.frameNum;
			prevPocMsb = mmco5 ? 0 : pocMsb;
			prevPocLsb = mmco5 ? current.topPoc : static_cast<int32_t>(first.pocLsb);
		}

		dpb.push_back(current);
		started = true;

		return Bump(maxNumReorder) ? Result::kOk : Result::kFailed;
	}

	void VaapiH264Decoder::PictureOrderCount(const H264SliceHeader & header, const H264Sps & sps, int32_t & top,
		int32_t & bottom)
	{
		const bool idr = header.IsIdr();
		const uint32_t maxFrameNum = sps.MaxFrameNum();

		if (sps.pocType == 0) {
			const int32_t maxPocLsb = 1 << (sps.log2MaxPocLsbMinus4 + 4);
			const int32_t pocLsb = static_cast<int32_t>(header.pocLsb);
			const int32_t prevMsb = idr ? 0 : prevPocMsb;
			const int32_t prevLsb = idr ? 0 : prevPocLsb;

			pocMsb = prevMsb;

			if (pocLsb < prevLsb && prevLsb - pocLsb >= maxPocLsb / 2) {
				pocMsb = prevMsb + maxPocLsb;
			}
			else if (pocLsb > prevLsb && pocLsb - prevLsb > maxPocLsb / 2) {
				pocMsb = prevMsb - maxPocLsb;
			}

			top = pocMsb + pocLsb;
			bottom = top + header.deltaPocBottom;

			return;
		}

		// FrameNumOffset (8-6, 8-11) for the other two types.
		if (idr) {
			frameNumOffset = 0;
		}
		else if (prevFrameNum > header.frameNum) {
			frameNumOffset = prevFrameNumOffset + maxFrameNum;
		}
		else {
			frameNumOffset = prevFrameNumOffset;
		}

		if (sps.pocType == 1) {
			const uint32_t cycleLength = static_cast<uint32_t>(sps.offsetForRefFrame.size());
			uint32_t absFrameNum = cycleLength != 0 ? frameNumOffset + header.frameNum : 0;

			if (header.nalRefIdc == 0 && absFrameNum > 0) {
				absFrameNum--;
			}

			int32_t expected = 0;

			if (absFrameNum > 0) {
				int32_t deltaPerCycle = 0;

				for (int32_t offset : sps.offsetForRefFrame) {
					deltaPerCycle += offset;
				}

				const uint32_t cycleCount = (absFrameNum - 1) / cycleLength;
				const uint32_t inCycle = (absFrameNum - 1) % cycleLength;

				expected = static_cast<int32_t>(cycleCount) * deltaPerCycle;

				for (uint32_t i = 0; i <= inCycle; i++) {
					expected += sps.offsetForRefFrame[i];
				}
			}

			if (header.nalRefIdc == 0) {
				expected += sps.offsetForNonRefPic;
			}

			top = expected + header.deltaPoc[0];
			bottom = top + sps.offsetForTopToBottomField + header.deltaPoc[1];

			return;
		}

		// Type 2: the order of decoding.
		int32_t temp = 0;

		if (!idr) {
			temp = 2 * static_cast<int32_t>(frameNumOffset + header.frameNum) - (header.nalRefIdc == 0 ? 1 : 0);
		}

		top = temp;
		bottom = temp;
	}

	VaapiH264Decoder::Result VaapiH264Decoder::MarkReferences(const H264SliceHeader & header, const H264Sps & sps,
		Picture & current)
	{
		const uint32_t maxFrameNum = sps.MaxFrameNum();

		if (header.IsIdr()) {
			for (Picture & picture : dpb) {
				picture.shortTerm = false;
				picture.longTerm = false;
			}

			if (header.longTermReference) {
				current.longTerm = true;
				current.longTermFrameIdx = 0;
				maxLongTermFrameIdxPlus1 = 1;
			}
			else {
				current.shortTerm = true;
				maxLongTermFrameIdxPlus1 = 0;
			}

			return Result::kOk;
		}

		const auto shortTermByPicNum = [&](int32_t picNum) -> Picture * {
			for (Picture & picture : dpb) {
				if (picture.shortTerm && PicNum(picture, header.frameNum, maxFrameNum) == picNum) {
					return &picture;
				}
			}

			return nullptr;
		};

		const auto releaseLongTermIdx = [&](uint32_t idx, const Picture * except) {
			for (Picture & picture : dpb) {
				if (picture.longTerm && picture.longTermFrameIdx == idx && &picture != except) {
					picture.longTerm = false;
				}
			}
		};

		if (header.adaptiveRefPicMarking) {
			for (const H264Operation & operation : header.memoryManagement) {
				const int32_t picNumX = static_cast<int32_t>(header.frameNum) - static_cast<int32_t>(operation.value + 1);

				switch (operation.operation) {
					case 1:
						if (Picture * picture = shortTermByPicNum(picNumX)) {
							picture->shortTerm = false;
						}
						break;

					case 2:
						for (Picture & picture : dpb) {
							if (picture.longTerm && picture.longTermFrameIdx == operation.value) {
								picture.longTerm = false;
							}
						}
						break;

					case 3:
						if (Picture * picture = shortTermByPicNum(picNumX)) {
							releaseLongTermIdx(operation.longTermFrameIdx, picture);

							picture->shortTerm = false;
							picture->longTerm = true;
							picture->longTermFrameIdx = operation.longTermFrameIdx;
						}
						break;

					case 4:
						maxLongTermFrameIdxPlus1 = operation.value;

						for (Picture & picture : dpb) {
							if (picture.longTerm && picture.longTermFrameIdx >= maxLongTermFrameIdxPlus1) {
								picture.longTerm = false;
							}
						}
						break;

					case 5:
						for (Picture & picture : dpb) {
							picture.shortTerm = false;
							picture.longTerm = false;
						}

						maxLongTermFrameIdxPlus1 = 0;
						mmco5 = true;
						break;

					case 6:
						releaseLongTermIdx(operation.longTermFrameIdx, nullptr);

						current.longTerm = true;
						current.longTermFrameIdx = operation.longTermFrameIdx;
						break;
				}
			}
		}
		else {
			// The sliding window (8.2.5.3): the oldest short-term frame goes
			// when the buffer of references is full.
			const auto references = std::count_if(dpb.begin(), dpb.end(), [](const Picture & picture) {
				return picture.IsReference();
			});

			if (references >= static_cast<long>(std::max(sps.maxNumRefFrames, 1u))) {
				Picture * oldest = nullptr;

				for (Picture & picture : dpb) {
					if (picture.shortTerm && (oldest == nullptr
						|| PicNum(picture, header.frameNum, maxFrameNum) < PicNum(*oldest, header.frameNum, maxFrameNum)))
					{
						oldest = &picture;
					}
				}

				if (oldest != nullptr) {
					oldest->shortTerm = false;
				}
			}
		}

		if (!current.longTerm) {
			current.shortTerm = true;
		}

		if (mmco5) {
			// The picture counts as the first of a new sequence (8.2.1).
			const int32_t temp = current.Poc();

			current.topPoc -= temp;
			current.bottomPoc -= temp;
			current.frameNum = 0;
		}

		return Result::kOk;
	}

	int32_t VaapiH264Decoder::PicNum(const Picture & picture, uint32_t currentFrameNum, uint32_t maxFrameNum) const
	{
		// FrameNumWrap (8-27), which is PicNum for frames.
		return picture.frameNum > currentFrameNum ? static_cast<int32_t>(picture.frameNum) - static_cast<int32_t>(maxFrameNum)
			: static_cast<int32_t>(picture.frameNum);
	}

	bool VaapiH264Decoder::BuildReferenceLists(const H264SliceHeader & header, const H264Sps & sps,
		int32_t currentPoc, std::vector<int> & list0, std::vector<int> & list1) const
	{
		list0.clear();
		list1.clear();

		if (header.IsIntra()) {
			return true;
		}

		const uint32_t maxFrameNum = sps.MaxFrameNum();
		std::vector<const Picture *> shortTerm;
		std::vector<const Picture *> longTerm;

		for (const Picture & picture : dpb) {
			if (picture.shortTerm) {
				shortTerm.push_back(&picture);
			}
			else if (picture.longTerm) {
				longTerm.push_back(&picture);
			}
		}

		std::sort(longTerm.begin(), longTerm.end(), [](const Picture * a, const Picture * b) {
			return a->longTermFrameIdx < b->longTermFrameIdx;
		});

		std::vector<int> initial[2];

		if (!header.IsB()) {
			// 8.2.4.2.1: by PicNum, highest first.
			std::sort(shortTerm.begin(), shortTerm.end(), [&](const Picture * a, const Picture * b) {
				return PicNum(*a, header.frameNum, maxFrameNum) > PicNum(*b, header.frameNum, maxFrameNum);
			});

			for (const Picture * picture : shortTerm) {
				initial[0].push_back(picture->surface);
			}
		}
		else {
			// 8.2.4.2.3: the frames before the current one, nearest first,
			// then those after it, and the other way round for list 1.
			std::vector<const Picture *> before;
			std::vector<const Picture *> after;

			for (const Picture * picture : shortTerm) {
				(picture->Poc() < currentPoc ? before : after).push_back(picture);
			}

			std::sort(before.begin(), before.end(), [](const Picture * a, const Picture * b) {
				return a->Poc() > b->Poc();
			});
			std::sort(after.begin(), after.end(), [](const Picture * a, const Picture * b) {
				return a->Poc() < b->Poc();
			});

			for (const Picture * picture : before) {
				initial[0].push_back(picture->surface);
			}
			for (const Picture * picture : after) {
				initial[0].push_back(picture->surface);
				initial[1].push_back(picture->surface);
			}
			for (const Picture * picture : before) {
				initial[1].push_back(picture->surface);
			}
		}

		for (int list = 0; list < (header.IsB() ? 2 : 1); list++) {
			for (const Picture * picture : longTerm) {
				initial[list].push_back(picture->surface);
			}
		}

		if (header.IsB() && initial[1].size() > 1 && initial[1] == initial[0]) {
			std::swap(initial[1][0], initial[1][1]);
		}

		// The modifications (8.2.4.3).
		for (int list = 0; list < (header.IsB() ? 2 : 1); list++) {
			const uint32_t numActive = (list == 0 ? header.numRefIdxL0Minus1 : header.numRefIdxL1Minus1) + 1;
			std::vector<int> & entries = initial[list];

			entries.resize(numActive + 1, -1);

			const int32_t maxPicNum = static_cast<int32_t>(maxFrameNum);
			int32_t picNumPred = static_cast<int32_t>(header.frameNum);
			uint32_t refIdx = 0;

			for (const H264Operation & modification : header.listModifications[list]) {
				if (refIdx >= numActive) {
					return false;
				}

				const Picture * target = nullptr;
				int32_t picNum = 0;

				if (modification.operation == 2) {
					for (const Picture * picture : longTerm) {
						if (picture->longTermFrameIdx == modification.value) {
							target = picture;
						}
					}
				}
				else {
					const int32_t absDiff = static_cast<int32_t>(modification.value) + 1;

					if (absDiff > maxPicNum) {
						return false;
					}

					int32_t picNumNoWrap = modification.operation == 0 ? picNumPred - absDiff : picNumPred + absDiff;

					if (picNumNoWrap < 0) {
						picNumNoWrap += maxPicNum;
					}
					else if (picNumNoWrap >= maxPicNum) {
						picNumNoWrap -= maxPicNum;
					}

					picNumPred = picNumNoWrap;
					picNum = picNumNoWrap > static_cast<int32_t>(header.frameNum) ? picNumNoWrap - maxPicNum : picNumNoWrap;

					for (const Picture * picture : shortTerm) {
						if (PicNum(*picture, header.frameNum, maxFrameNum) == picNum) {
							target = picture;
						}
					}
				}

				if (target == nullptr) {
					return false;
				}

				// The picture goes to refIdx, and its later entry out.
				for (uint32_t c = numActive; c > refIdx; c--) {
					entries[c] = entries[c - 1];
				}

				entries[refIdx++] = target->surface;

				uint32_t n = refIdx;

				for (uint32_t c = refIdx; c <= numActive; c++) {
					if (entries[c] != target->surface) {
						entries[n++] = entries[c];
					}
				}
			}

			entries.resize(numActive);
		}

		list0 = std::move(initial[0]);

		if (header.IsB()) {
			list1 = std::move(initial[1]);
		}

		return true;
	}

	VAPictureH264 VaapiH264Decoder::ToVaPicture(const Picture & picture) const
	{
		VAPictureH264 vaPicture = {};
		vaPicture.picture_id = session.Surface(picture.surface);
		vaPicture.frame_idx = picture.longTerm ? picture.longTermFrameIdx : picture.frameNum;
		vaPicture.flags = picture.longTerm ? VA_PICTURE_H264_LONG_TERM_REFERENCE
			: (picture.shortTerm ? VA_PICTURE_H264_SHORT_TERM_REFERENCE : 0);
		vaPicture.TopFieldOrderCnt = picture.topPoc;
		vaPicture.BottomFieldOrderCnt = picture.bottomPoc;

		return vaPicture;
	}

	const VaapiH264Decoder::Picture * VaapiH264Decoder::FindBySurface(int surface) const
	{
		for (const Picture & picture : dpb) {
			if (picture.surface == surface) {
				return &picture;
			}
		}

		return nullptr;
	}

	bool VaapiH264Decoder::Bump(uint32_t keep)
	{
		while (true) {
			auto next = dpb.end();
			uint32_t waiting = 0;

			for (auto picture = dpb.begin(); picture != dpb.end(); ++picture) {
				if (!picture->waitingOutput) {
					continue;
				}

				waiting++;

				if (next == dpb.end() || picture->Poc() < next->Poc()) {
					next = picture;
				}
			}

			if (waiting <= keep || next == dpb.end()) {
				return true;
			}

			next->waitingOutput = false;

			if (!Output(*next)) {
				return false;
			}

			RemoveUnused();
		}
	}

	void VaapiH264Decoder::RemoveUnused()
	{
		dpb.erase(std::remove_if(dpb.begin(), dpb.end(), [](const Picture & picture) {
			return !picture.IsReference() && !picture.waitingOutput;
		}), dpb.end());
	}

	bool VaapiH264Decoder::Output(const Picture & picture)
	{
		webrtc::scoped_refptr<webrtc::I420Buffer> i420 = session.Download(picture.surface, cropX, cropY, width,
			height);

		if (!i420) {
			return false;
		}

		webrtc::VideoFrame frame = webrtc::VideoFrame::Builder()
			.set_video_frame_buffer(i420)
			.set_rtp_timestamp(picture.rtpTimestamp)
			.set_timestamp_ms(picture.renderTimeMs)
			.set_ntp_time_ms(picture.ntpTimeMs)
			.set_rotation(picture.rotation)
			.build();

		callback->Decoded(frame, std::nullopt, std::nullopt);

		return true;
	}

	int VaapiH264Decoder::FreeSurface() const
	{
		for (int surface = 0; surface < session.SurfaceCount(); surface++) {
			if (FindBySurface(surface) == nullptr) {
				return surface;
			}
		}

		return -1;
	}
}
