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

#include "media/video/codec/linux/VaapiH265Decoder.h"

#include "api/video/video_frame.h"
#include "common_video/h265/h265_common.h"
#include "modules/video_coding/include/video_error_codes.h"
#include "rtc_base/logging.h"

#include <algorithm>

namespace jni
{
	namespace
	{
		// The reference frames VA-API takes.
		constexpr size_t kMaxReferenceFrames = 15;

		constexpr uint8_t kInvalidReference = 0xFF;

		// NAL unit types (table 7-1) of the pictures that matter to the
		// picture order count.
		constexpr uint8_t kNalRadlN = 6;
		constexpr uint8_t kNalRaslR = 9;
		constexpr uint8_t kNalVclMax = 31;
		constexpr uint8_t kNalReservedNonIrapMin = 10;
		constexpr uint8_t kNalReservedNonIrapMax = 15;
		constexpr uint8_t kNalReservedIrap22 = 22;

		bool IsIrap(uint8_t type)
		{
			return type >= H265Parser::kNalBlaWLp && type <= H265Parser::kNalReservedIrap23;
		}

		bool IsIdr(uint8_t type)
		{
			return type == H265Parser::kNalIdrWRadl || type == H265Parser::kNalIdrNLp;
		}

		bool IsRasl(uint8_t type)
		{
			return type == H265Parser::kNalRaslN || type == H265Parser::kNalRaslR;
		}

		// RADL, RASL and sub-layer non-reference pictures.
		bool SkippedForPrevTid0(uint8_t type)
		{
			return (type >= kNalRadlN && type <= kNalRaslR) || (type <= 14 && type % 2 == 0);
		}

		void InvalidatePicture(VAPictureHEVC & picture)
		{
			picture = {};
			picture.picture_id = VA_INVALID_SURFACE;
			picture.flags = VA_PICTURE_HEVC_INVALID;
		}
	}

	bool VaapiH265Decoder::Picture::IsReference() const
	{
		return shortTerm || longTerm;
	}

	VaapiH265Decoder::VaapiH265Decoder(VaapiLibrary & library) :
		library(library),
		implementationName("VA-API (" + library.Vendor() + ")"),
		callback(nullptr),
		session(library, implementationName + " H.265"),
		codedWidth(0),
		codedHeight(0),
		maxDecPicBuffering(0),
		maxNumReorder(0),
		cropX(0),
		cropY(0),
		width(0),
		height(0),
		prevTid0Poc(0),
		skipRasl(false),
		started(false),
		failed(false)
	{
	}

	VaapiH265Decoder::~VaapiH265Decoder()
	{
		Release();
	}

	bool VaapiH265Decoder::Configure(const Settings & settings)
	{
		if (settings.codec_type() != webrtc::kVideoCodecH265) {
			return false;
		}

		Release();

		RTC_LOG(LS_INFO) << implementationName << " H.265 decoder configured";

		return true;
	}

	int32_t VaapiH265Decoder::Decode(const webrtc::EncodedImage & image, int64_t renderTimeMs)
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
				RTC_LOG(LS_INFO) << implementationName << " H.265: a picture refers to one that is not there; "
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

		for (const webrtc::H265::NaluIndex & index : webrtc::H265::FindNaluIndices(data)) {
			const std::span<const uint8_t> nal = data.subspan(index.payload_start_offset, index.payload_size);

			if (nal.size() < 2) {
				continue;
			}

			const uint8_t type = (nal[0] >> 1) & 0x3F;
			// Only the base layer.
			const uint8_t layerId = static_cast<uint8_t>(((nal[0] & 1) << 5) | (nal[1] >> 3));

			if (layerId != 0) {
				continue;
			}

			if (type == H265Parser::kNalSps || type == H265Parser::kNalPps) {
				// The parameter sets of the next picture.
				if (!slices.empty()) {
					if (const int32_t result = decode(); result != WEBRTC_VIDEO_CODEC_OK) {
						return result;
					}
				}

				const bool parsed = type == H265Parser::kNalSps ? parser.ParseSps(nal) : parser.ParsePps(nal);

				if (!parsed) {
					RTC_LOG(LS_INFO) << implementationName << " H.265: a parameter set that is not 8 bit 4:2:0 "
						"Main, or one the decoder does not take";
					failed = true;

					return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
				}

				continue;
			}

			if (type == H265Parser::kNalEos) {
				// The next picture starts anew, as the first one does.
				if (!slices.empty()) {
					if (const int32_t result = decode(); result != WEBRTC_VIDEO_CODEC_OK) {
						return result;
					}
				}

				started = false;
				continue;
			}

			// Reserved picture types are ignored, as are all other NAL units.
			if (type > kNalVclMax || type >= kNalReservedIrap22
				|| (type >= kNalReservedNonIrapMin && type <= kNalReservedNonIrapMax))
			{
				continue;
			}

			std::optional<H265SliceHeader> header = parser.ParseSliceHeader(nal);

			if (!header) {
				RTC_LOG(LS_INFO) << implementationName << " H.265: a slice header that is broken, or refers to "
					"a parameter set that is not there";
				failed = true;

				return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
			}

			if (header->firstSliceInPicture && !slices.empty()) {
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

	int32_t VaapiH265Decoder::RegisterDecodeCompleteCallback(webrtc::DecodedImageCallback * decodeCallback)
	{
		callback = decodeCallback;

		return WEBRTC_VIDEO_CODEC_OK;
	}

	int32_t VaapiH265Decoder::Release()
	{
		DestroySession();

		parser.Reset();
		prevTid0Poc = 0;
		skipRasl = false;
		started = false;
		failed = false;

		return WEBRTC_VIDEO_CODEC_OK;
	}

	webrtc::VideoDecoder::DecoderInfo VaapiH265Decoder::GetDecoderInfo() const
	{
		DecoderInfo info;
		info.implementation_name = implementationName;
		info.is_hardware_accelerated = true;

		return info;
	}

	const char * VaapiH265Decoder::ImplementationName() const
	{
		return implementationName.c_str();
	}

	VaapiH265Decoder::Result VaapiH265Decoder::DecodePicture(const std::vector<Slice> & slices,
		const webrtc::EncodedImage & image, int64_t renderTimeMs)
	{
		const H265SliceHeader & first = slices.front().header;

		if (!first.firstSliceInPicture) {
			return Result::kMissingReference;
		}

		const H265Pps * pps = parser.Pps(first.ppsId);
		const H265Sps * sps = pps != nullptr ? parser.Sps(pps->spsId) : nullptr;

		if (sps == nullptr) {
			return Result::kFailed;
		}

		if (sps->scalingListEnabled || pps->scalingListDataPresent || pps->extensionPresent) {
			RTC_LOG(LS_INFO) << implementationName << " H.265: a stream with scaling lists or extensions";
			return Result::kFailed;
		}

		const uint8_t type = first.nalType;
		const bool irap = IsIrap(type);

		// The leading pictures of a CRA picture that decoding starts with
		// refer to pictures before it, which are not there.
		if (IsRasl(type) && skipRasl) {
			return Result::kOk;
		}

		if (!irap && !started) {
			return Result::kMissingReference;
		}

		// An IDR or BLA picture, or a CRA picture that decoding starts with,
		// begins a new coded video sequence (NoRaslOutputFlag).
		const bool noRaslOutput = irap && (IsIdr(type) || type < H265Parser::kNalCraNut || !started);

		if (irap) {
			skipRasl = noRaslOutput;
		}

		if (noRaslOutput) {
			// The pictures of the sequence before are output, unless the
			// picture says not to, and all references are let go.
			if (first.noOutputOfPriorPics) {
				dpb.clear();
			}
			else if (!Bump(0)) {
				return Result::kFailed;
			}

			dpb.clear();

			const uint32_t spsMaxDecPicBuffering = sps->maxDecPicBufferingMinus1 + 1;

			if (!session.IsCreated() || sps->width != codedWidth || sps->height != codedHeight
				|| spsMaxDecPicBuffering != maxDecPicBuffering || sps->maxNumReorderPics != maxNumReorder)
			{
				if (!CreateSession(*sps)) {
					return Result::kFailed;
				}
			}
		}
		else if (sps->width != codedWidth || sps->height != codedHeight) {
			RTC_LOG(LS_INFO) << implementationName << " H.265: the size changes without a key frame";
			return Result::kFailed;
		}

		cropX = sps->cropLeft;
		cropY = sps->cropTop;
		width = sps->width - sps->cropLeft - sps->cropRight;
		height = sps->height - sps->cropTop - sps->cropBottom;

		const int32_t poc = PictureOrderCount(first, noRaslOutput);
		const Result rps = ApplyReferencePictureSet(first, *sps, poc);

		if (rps != Result::kOk) {
			return rps;
		}

		// The pictures neither referenced nor waiting for output are gone; and
		// a full buffer makes room, in output order (C.5.2.2).
		RemoveUnused();

		while (dpb.size() >= maxDecPicBuffering) {
			const uint32_t waiting = static_cast<uint32_t>(std::count_if(dpb.begin(), dpb.end(),
				[](const Picture & picture) {
					return picture.waitingOutput;
				}));

			// Only references are left, which the stream should not have.
			if (waiting == 0) {
				break;
			}
			if (!Bump(waiting - 1)) {
				return Result::kFailed;
			}
		}

		const int surface = FreeSurface();

		if (surface < 0) {
			RTC_LOG(LS_WARNING) << implementationName << " H.265: no surface is free";
			return Result::kFailed;
		}

		VAPictureParameterBufferHEVC pictureParameters = {};
		FillPicture(pictureParameters, *sps, *pps, first, surface);
		pictureParameters.CurrPic.pic_order_cnt = poc;

		std::vector<VASliceParameterBufferHEVC> sliceParameters(slices.size());

		for (size_t i = 0; i < slices.size(); i++) {
			if (!FillSlice(sliceParameters[i], slices[i], i + 1 == slices.size())) {
				return Result::kMissingReference;
			}
		}

		if (!Render(pictureParameters, sliceParameters, slices, surface)) {
			return Result::kFailed;
		}

		Picture picture;
		picture.surface = surface;
		picture.poc = poc;
		picture.shortTerm = true;
		picture.waitingOutput = first.picOutput;
		picture.rtpTimestamp = image.RtpTimestamp();
		picture.ntpTimeMs = image.ntp_time_ms_;
		picture.renderTimeMs = renderTimeMs;
		picture.rotation = image.rotation_;

		dpb.push_back(picture);

		if (first.temporalId == 0 && !SkippedForPrevTid0(type)) {
			prevTid0Poc = poc;
		}

		started = true;

		return Bump(maxNumReorder) ? Result::kOk : Result::kFailed;
	}

	int32_t VaapiH265Decoder::PictureOrderCount(const H265SliceHeader & header, bool noRaslOutput)
	{
		const H265Sps * sps = parser.Sps(parser.Pps(header.ppsId)->spsId);
		const int32_t maxPocLsb = 1 << (sps->log2MaxPocLsbMinus4 + 4);
		const int32_t pocLsb = static_cast<int32_t>(header.pocLsb);

		if (IsIrap(header.nalType) && noRaslOutput) {
			return pocLsb;
		}

		// 8.3.1, from the picture order count of prevTid0Pic.
		const int32_t prevLsb = prevTid0Poc & (maxPocLsb - 1);
		const int32_t prevMsb = prevTid0Poc - prevLsb;
		int32_t msb = prevMsb;

		if (pocLsb < prevLsb && prevLsb - pocLsb >= maxPocLsb / 2) {
			msb = prevMsb + maxPocLsb;
		}
		else if (pocLsb > prevLsb && pocLsb - prevLsb > maxPocLsb / 2) {
			msb = prevMsb - maxPocLsb;
		}

		return msb + pocLsb;
	}

	VaapiH265Decoder::Result VaapiH265Decoder::ApplyReferencePictureSet(const H265SliceHeader & header,
		const H265Sps & sps, int32_t poc)
	{
		stCurrBefore.clear();
		stCurrAfter.clear();
		ltCurr.clear();

		const int32_t maxPocLsb = 1 << (sps.log2MaxPocLsbMinus4 + 4);
		const H265ShortTermRps & rps = header.shortTermRps;
		// Whether each picture stays a short-term or a long-term reference.
		std::vector<bool> shortTerm(dpb.size(), false);
		std::vector<bool> longTerm(dpb.size(), false);

		if (!IsIdr(header.nalType)) {
			// The long-term pictures first, by their POC or its LSBs.
			for (size_t i = 0; i < header.longTermPoc.size(); i++) {
				int32_t ltPoc = header.longTermPoc[i];

				if (header.longTermMsbPresent[i]) {
					ltPoc += poc - (poc & (maxPocLsb - 1));
				}

				int found = -1;

				for (size_t j = 0; j < dpb.size(); j++) {
					const int32_t candidate = header.longTermMsbPresent[i] ? dpb[j].poc
						: (dpb[j].poc & (maxPocLsb - 1));

					if (dpb[j].IsReference() && !longTerm[j] && candidate == ltPoc) {
						found = static_cast<int>(j);
						break;
					}
				}

				if (found < 0) {
					if (header.longTermUsed[i]) {
						return Result::kMissingReference;
					}

					continue;
				}

				longTerm[found] = true;

				if (header.longTermUsed[i]) {
					ltCurr.push_back(dpb[found].surface);
				}
			}

			const auto findShortTerm = [&](int32_t target, bool used, std::vector<int> & list) {
				for (size_t j = 0; j < dpb.size(); j++) {
					if (dpb[j].shortTerm && !longTerm[j] && !shortTerm[j] && dpb[j].poc == target) {
						shortTerm[j] = true;

						if (used) {
							list.push_back(dpb[j].surface);
						}

						return true;
					}
				}

				return !used;
			};

			for (int i = 0; i < rps.numNegative; i++) {
				if (!findShortTerm(poc + rps.deltaPocS0[i], rps.usedS0[i], stCurrBefore)) {
					return Result::kMissingReference;
				}
			}
			for (int i = 0; i < rps.numPositive; i++) {
				if (!findShortTerm(poc + rps.deltaPocS1[i], rps.usedS1[i], stCurrAfter)) {
					return Result::kMissingReference;
				}
			}
		}

		// The pictures in no subset are no longer references.
		for (size_t j = 0; j < dpb.size(); j++) {
			dpb[j].shortTerm = shortTerm[j];
			dpb[j].longTerm = longTerm[j];
		}

		return Result::kOk;
	}

	bool VaapiH265Decoder::BuildReferenceLists(const H265SliceHeader & header, std::vector<int> & list0,
		std::vector<int> & list1) const
	{
		list0.clear();
		list1.clear();

		if (header.IsIntra()) {
			return true;
		}

		const size_t total = stCurrBefore.size() + stCurrAfter.size() + ltCurr.size();

		if (total == 0) {
			return false;
		}

		const auto build = [&](uint32_t numActiveMinus1, bool modification,
			const std::array<uint32_t, H265SliceHeader::kMaxRefs> & entries, const std::vector<int> & firstSet,
			const std::vector<int> & secondSet, std::vector<int> & list)
		{
			const size_t numTemp = std::max<size_t>(numActiveMinus1 + 1, total);
			std::vector<int> temp;

			while (temp.size() < numTemp) {
				for (const std::vector<int> * set : { &firstSet, &secondSet, &ltCurr }) {
					for (int index : *set) {
						if (temp.size() < numTemp) {
							temp.push_back(index);
						}
					}
				}
			}

			for (uint32_t i = 0; i <= numActiveMinus1; i++) {
				const uint32_t entry = modification ? entries[i] : i;

				if (entry >= temp.size()) {
					return false;
				}

				list.push_back(temp[entry]);
			}

			return true;
		};

		if (!build(header.numRefIdxL0Minus1, header.listModificationL0, header.listEntryL0, stCurrBefore, stCurrAfter,
			list0))
		{
			return false;
		}

		return !header.IsB() || build(header.numRefIdxL1Minus1, header.listModificationL1, header.listEntryL1,
			stCurrAfter, stCurrBefore, list1);
	}

	void VaapiH265Decoder::FillPicture(VAPictureParameterBufferHEVC & parameters, const H265Sps & sps,
		const H265Pps & pps, const H265SliceHeader & header, int surface) const
	{
		parameters.CurrPic.picture_id = session.Surface(surface);
		parameters.CurrPic.flags = 0;

		for (VAPictureHEVC & reference : parameters.ReferenceFrames) {
			InvalidatePicture(reference);
		}

		size_t count = 0;

		for (size_t j = 0; j < dpb.size() && count < kMaxReferenceFrames; j++) {
			if (!dpb[j].IsReference()) {
				continue;
			}

			const int index = dpb[j].surface;
			VAPictureHEVC & reference = parameters.ReferenceFrames[count++];
			reference.picture_id = session.Surface(dpb[j].surface);
			reference.pic_order_cnt = dpb[j].poc;
			reference.flags = dpb[j].longTerm ? VA_PICTURE_HEVC_LONG_TERM_REFERENCE : 0;

			if (std::find(stCurrBefore.begin(), stCurrBefore.end(), index) != stCurrBefore.end()) {
				reference.flags |= VA_PICTURE_HEVC_RPS_ST_CURR_BEFORE;
			}
			else if (std::find(stCurrAfter.begin(), stCurrAfter.end(), index) != stCurrAfter.end()) {
				reference.flags |= VA_PICTURE_HEVC_RPS_ST_CURR_AFTER;
			}
			else if (std::find(ltCurr.begin(), ltCurr.end(), index) != ltCurr.end()) {
				reference.flags |= VA_PICTURE_HEVC_RPS_LT_CURR;
			}
		}

		parameters.pic_width_in_luma_samples = static_cast<uint16_t>(sps.width);
		parameters.pic_height_in_luma_samples = static_cast<uint16_t>(sps.height);

		auto & fields = parameters.pic_fields.bits;
		fields.chroma_format_idc = sps.chromaFormatIdc;
		fields.separate_colour_plane_flag = sps.separateColourPlane ? 1 : 0;
		fields.pcm_enabled_flag = sps.pcm ? 1 : 0;
		fields.scaling_list_enabled_flag = sps.scalingListEnabled ? 1 : 0;
		fields.transform_skip_enabled_flag = pps.transformSkip ? 1 : 0;
		fields.amp_enabled_flag = sps.amp ? 1 : 0;
		fields.strong_intra_smoothing_enabled_flag = sps.strongIntraSmoothing ? 1 : 0;
		fields.sign_data_hiding_enabled_flag = pps.signDataHiding ? 1 : 0;
		fields.constrained_intra_pred_flag = pps.constrainedIntraPred ? 1 : 0;
		fields.cu_qp_delta_enabled_flag = pps.cuQpDelta ? 1 : 0;
		fields.weighted_pred_flag = pps.weightedPred ? 1 : 0;
		fields.weighted_bipred_flag = pps.weightedBipred ? 1 : 0;
		fields.transquant_bypass_enabled_flag = pps.transquantBypass ? 1 : 0;
		fields.tiles_enabled_flag = pps.tiles ? 1 : 0;
		fields.entropy_coding_sync_enabled_flag = pps.entropyCodingSync ? 1 : 0;
		fields.pps_loop_filter_across_slices_enabled_flag = pps.loopFilterAcrossSlices ? 1 : 0;
		fields.loop_filter_across_tiles_enabled_flag = pps.loopFilterAcrossTiles ? 1 : 0;
		fields.pcm_loop_filter_disabled_flag = sps.pcmLoopFilterDisabled ? 1 : 0;

		parameters.sps_max_dec_pic_buffering_minus1 = static_cast<uint8_t>(sps.maxDecPicBufferingMinus1);
		parameters.bit_depth_luma_minus8 = static_cast<uint8_t>(sps.bitDepthLumaMinus8);
		parameters.bit_depth_chroma_minus8 = static_cast<uint8_t>(sps.bitDepthChromaMinus8);
		parameters.pcm_sample_bit_depth_luma_minus1 = static_cast<uint8_t>(sps.pcmBitDepthLumaMinus1);
		parameters.pcm_sample_bit_depth_chroma_minus1 = static_cast<uint8_t>(sps.pcmBitDepthChromaMinus1);
		parameters.log2_min_luma_coding_block_size_minus3 = static_cast<uint8_t>(sps.log2MinCbMinus3);
		parameters.log2_diff_max_min_luma_coding_block_size = static_cast<uint8_t>(sps.log2DiffMaxMinCb);
		parameters.log2_min_transform_block_size_minus2 = static_cast<uint8_t>(sps.log2MinTbMinus2);
		parameters.log2_diff_max_min_transform_block_size = static_cast<uint8_t>(sps.log2DiffMaxMinTb);
		parameters.log2_min_pcm_luma_coding_block_size_minus3 = static_cast<uint8_t>(sps.log2MinPcmCbMinus3);
		parameters.log2_diff_max_min_pcm_luma_coding_block_size = static_cast<uint8_t>(sps.log2DiffMaxMinPcmCb);
		parameters.max_transform_hierarchy_depth_intra = static_cast<uint8_t>(sps.maxTransformDepthIntra);
		parameters.max_transform_hierarchy_depth_inter = static_cast<uint8_t>(sps.maxTransformDepthInter);
		parameters.init_qp_minus26 = static_cast<int8_t>(pps.initQpMinus26);
		parameters.diff_cu_qp_delta_depth = static_cast<uint8_t>(pps.diffCuQpDeltaDepth);
		parameters.pps_cb_qp_offset = static_cast<int8_t>(pps.cbQpOffset);
		parameters.pps_cr_qp_offset = static_cast<int8_t>(pps.crQpOffset);
		parameters.log2_parallel_merge_level_minus2 = static_cast<uint8_t>(pps.log2ParallelMergeLevelMinus2);

		if (pps.tiles) {
			const uint32_t columns = pps.numTileColumnsMinus1 + 1;
			const uint32_t rows = pps.numTileRowsMinus1 + 1;
			const uint32_t widthInCtbs = sps.WidthInCtbs();
			const uint32_t heightInCtbs = sps.HeightInCtbs();

			parameters.num_tile_columns_minus1 = static_cast<uint8_t>(pps.numTileColumnsMinus1);
			parameters.num_tile_rows_minus1 = static_cast<uint8_t>(pps.numTileRowsMinus1);

			// The width of every column and the height of every row (6-3, 6-4),
			// the last ones taking what is left.
			uint32_t used = 0;

			for (uint32_t i = 0; i < columns; i++) {
				uint32_t columnWidth = pps.uniformSpacing
					? ((i + 1) * widthInCtbs) / columns - (i * widthInCtbs) / columns
					: (i + 1 < columns ? pps.columnWidthMinus1[i] + 1 : widthInCtbs - used);

				parameters.column_width_minus1[i] = static_cast<uint16_t>(columnWidth - 1);
				used += columnWidth;
			}

			used = 0;

			for (uint32_t i = 0; i < rows; i++) {
				uint32_t rowHeight = pps.uniformSpacing
					? ((i + 1) * heightInCtbs) / rows - (i * heightInCtbs) / rows
					: (i + 1 < rows ? pps.rowHeightMinus1[i] + 1 : heightInCtbs - used);

				parameters.row_height_minus1[i] = static_cast<uint16_t>(rowHeight - 1);
				used += rowHeight;
			}
		}

		auto & parsing = parameters.slice_parsing_fields.bits;
		parsing.lists_modification_present_flag = pps.listsModificationPresent ? 1 : 0;
		parsing.long_term_ref_pics_present_flag = sps.longTermRefsPresent ? 1 : 0;
		parsing.sps_temporal_mvp_enabled_flag = sps.temporalMvp ? 1 : 0;
		parsing.cabac_init_present_flag = pps.cabacInitPresent ? 1 : 0;
		parsing.output_flag_present_flag = pps.outputFlagPresent ? 1 : 0;
		parsing.dependent_slice_segments_enabled_flag = pps.dependentSliceSegments ? 1 : 0;
		parsing.pps_slice_chroma_qp_offsets_present_flag = pps.sliceChromaQpOffsetsPresent ? 1 : 0;
		parsing.sample_adaptive_offset_enabled_flag = sps.sao ? 1 : 0;
		parsing.deblocking_filter_override_enabled_flag = pps.deblockingFilterOverrideEnabled ? 1 : 0;
		parsing.pps_disable_deblocking_filter_flag = pps.deblockingFilterDisabled ? 1 : 0;
		parsing.slice_segment_header_extension_present_flag = pps.sliceHeaderExtensionPresent ? 1 : 0;
		parsing.RapPicFlag = IsIrap(header.nalType) ? 1 : 0;
		parsing.IdrPicFlag = IsIdr(header.nalType) ? 1 : 0;
		parsing.IntraPicFlag = IsIrap(header.nalType) ? 1 : 0;

		parameters.log2_max_pic_order_cnt_lsb_minus4 = static_cast<uint8_t>(sps.log2MaxPocLsbMinus4);
		parameters.num_short_term_ref_pic_sets = static_cast<uint8_t>(sps.shortTermRps.size());
		parameters.num_long_term_ref_pic_sps = static_cast<uint8_t>(sps.ltRefPicPocLsb.size());
		parameters.num_ref_idx_l0_default_active_minus1 = static_cast<uint8_t>(pps.numRefIdxL0DefaultMinus1);
		parameters.num_ref_idx_l1_default_active_minus1 = static_cast<uint8_t>(pps.numRefIdxL1DefaultMinus1);
		parameters.pps_beta_offset_div2 = static_cast<int8_t>(pps.betaOffsetDiv2);
		parameters.pps_tc_offset_div2 = static_cast<int8_t>(pps.tcOffsetDiv2);
		parameters.num_extra_slice_header_bits = static_cast<uint8_t>(pps.numExtraSliceHeaderBits);
		parameters.st_rps_bits = header.shortTermRpsBits;
	}

	bool VaapiH265Decoder::FillSlice(VASliceParameterBufferHEVC & parameters, const Slice & slice, bool last) const
	{
		const H265SliceHeader & header = slice.header;
		std::vector<int> lists[2];

		if (!BuildReferenceLists(header, lists[0], lists[1])) {
			return false;
		}

		parameters.slice_data_size = static_cast<uint32_t>(slice.nal.size());
		parameters.slice_data_offset = 0;
		parameters.slice_data_flag = VA_SLICE_DATA_FLAG_ALL;
		parameters.slice_data_byte_offset = header.dataByteOffset;
		parameters.slice_segment_address = header.segmentAddress;

		// The references, as indices into ReferenceFrames, which holds the
		// reference pictures in the order of the decoded picture buffer.
		std::fill(&parameters.RefPicList[0][0], &parameters.RefPicList[0][0] + 2 * 15, kInvalidReference);

		for (int list = 0; list < 2; list++) {
			for (size_t i = 0; i < lists[list].size() && i < kMaxReferenceFrames; i++) {
				const int frameIndex = ReferenceFrameIndex(lists[list][i]);

				if (frameIndex < 0) {
					return false;
				}

				parameters.RefPicList[list][i] = static_cast<uint8_t>(frameIndex);
			}
		}

		auto & flags = parameters.LongSliceFlags.fields;
		flags.LastSliceOfPic = last ? 1 : 0;
		flags.dependent_slice_segment_flag = header.dependent ? 1 : 0;
		flags.slice_type = header.sliceType;
		flags.color_plane_id = 0;
		flags.slice_sao_luma_flag = header.saoLuma ? 1 : 0;
		flags.slice_sao_chroma_flag = header.saoChroma ? 1 : 0;
		flags.mvd_l1_zero_flag = header.mvdL1Zero ? 1 : 0;
		flags.cabac_init_flag = header.cabacInit ? 1 : 0;
		flags.slice_temporal_mvp_enabled_flag = header.temporalMvp ? 1 : 0;
		flags.slice_deblocking_filter_disabled_flag = header.deblockingFilterDisabled ? 1 : 0;
		flags.collocated_from_l0_flag = header.collocatedFromL0 ? 1 : 0;
		flags.slice_loop_filter_across_slices_enabled_flag = header.loopFilterAcrossSlices ? 1 : 0;

		parameters.collocated_ref_idx = header.temporalMvp ? static_cast<uint8_t>(header.collocatedRefIdx)
			: kInvalidReference;
		parameters.num_ref_idx_l0_active_minus1 = header.IsIntra() ? 0 : static_cast<uint8_t>(header.numRefIdxL0Minus1);
		parameters.num_ref_idx_l1_active_minus1 = header.IsB() ? static_cast<uint8_t>(header.numRefIdxL1Minus1) : 0;
		parameters.slice_qp_delta = static_cast<int8_t>(header.qpDelta);
		parameters.slice_cb_qp_offset = static_cast<int8_t>(header.cbQpOffset);
		parameters.slice_cr_qp_offset = static_cast<int8_t>(header.crQpOffset);
		parameters.slice_beta_offset_div2 = static_cast<int8_t>(header.betaOffsetDiv2);
		parameters.slice_tc_offset_div2 = static_cast<int8_t>(header.tcOffsetDiv2);
		parameters.luma_log2_weight_denom = static_cast<uint8_t>(header.lumaLog2WeightDenom);
		parameters.delta_chroma_log2_weight_denom = static_cast<int8_t>(header.deltaChromaLog2WeightDenom);

		for (size_t i = 0; i < kMaxReferenceFrames; i++) {
			parameters.delta_luma_weight_l0[i] = static_cast<int8_t>(header.deltaLumaWeight[0][i]);
			parameters.luma_offset_l0[i] = static_cast<int8_t>(header.lumaOffset[0][i]);
			parameters.delta_luma_weight_l1[i] = static_cast<int8_t>(header.deltaLumaWeight[1][i]);
			parameters.luma_offset_l1[i] = static_cast<int8_t>(header.lumaOffset[1][i]);

			for (int j = 0; j < 2; j++) {
				parameters.delta_chroma_weight_l0[i][j] = static_cast<int8_t>(header.deltaChromaWeight[0][i][j]);
				parameters.ChromaOffsetL0[i][j] = static_cast<int8_t>(header.chromaOffset[0][i][j]);
				parameters.delta_chroma_weight_l1[i][j] = static_cast<int8_t>(header.deltaChromaWeight[1][i][j]);
				parameters.ChromaOffsetL1[i][j] = static_cast<int8_t>(header.chromaOffset[1][i][j]);
			}
		}

		parameters.five_minus_max_num_merge_cand = static_cast<uint8_t>(header.fiveMinusMaxNumMergeCand);
		parameters.num_entry_point_offsets = static_cast<uint16_t>(header.numEntryPoints);
		parameters.entry_offset_to_subset_array = 0;

		return true;
	}

	int VaapiH265Decoder::ReferenceFrameIndex(int surface) const
	{
		int index = 0;

		for (const Picture & picture : dpb) {
			if (!picture.IsReference()) {
				continue;
			}
			if (picture.surface == surface) {
				return index < static_cast<int>(kMaxReferenceFrames) ? index : -1;
			}

			index++;
		}

		return -1;
	}

	bool VaapiH265Decoder::Render(const VAPictureParameterBufferHEVC & picture,
		const std::vector<VASliceParameterBufferHEVC> & sliceParameters, const std::vector<Slice> & slices,
		int surface)
	{
		std::vector<VaapiDecodeSession::Buffer> buffers = {
			{ VAPictureParameterBufferType, &picture, sizeof(picture) }
		};

		for (size_t i = 0; i < slices.size(); i++) {
			buffers.push_back({ VASliceParameterBufferType, &sliceParameters[i], sizeof(sliceParameters[i]) });
			buffers.push_back({ VASliceDataBufferType, slices[i].nal.data(), slices[i].nal.size() });
		}

		return session.Decode(surface, buffers);
	}

	bool VaapiH265Decoder::Bump(uint32_t keep)
	{
		while (true) {
			auto next = dpb.end();
			uint32_t waiting = 0;

			for (auto picture = dpb.begin(); picture != dpb.end(); ++picture) {
				if (!picture->waitingOutput) {
					continue;
				}

				waiting++;

				if (next == dpb.end() || picture->poc < next->poc) {
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

	void VaapiH265Decoder::RemoveUnused()
	{
		dpb.erase(std::remove_if(dpb.begin(), dpb.end(), [](const Picture & picture) {
			return !picture.IsReference() && !picture.waitingOutput;
		}), dpb.end());
	}

	bool VaapiH265Decoder::Output(const Picture & picture)
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

	bool VaapiH265Decoder::CreateSession(const H265Sps & sps)
	{
		DestroySession();

		// The pictures the stream keeps, and the one being decoded.
		if (!session.Create(VAProfileHEVCMain, sps.width, sps.height, sps.maxDecPicBufferingMinus1 + 2)) {
			return false;
		}

		codedWidth = sps.width;
		codedHeight = sps.height;
		maxDecPicBuffering = sps.maxDecPicBufferingMinus1 + 1;
		maxNumReorder = sps.maxNumReorderPics;

		RTC_LOG(LS_INFO) << implementationName << " H.265 decoding " << sps.width - sps.cropLeft - sps.cropRight
			<< "x" << sps.height - sps.cropTop - sps.cropBottom;

		return true;
	}

	void VaapiH265Decoder::DestroySession()
	{
		session.Destroy();

		codedWidth = 0;
		codedHeight = 0;
		maxDecPicBuffering = 0;
		maxNumReorder = 0;
		dpb.clear();
		stCurrBefore.clear();
		stCurrAfter.clear();
		ltCurr.clear();
	}

	int VaapiH265Decoder::FreeSurface() const
	{
		for (int surface = 0; surface < session.SurfaceCount(); surface++) {
			const bool used = std::any_of(dpb.begin(), dpb.end(), [surface](const Picture & picture) {
				return picture.surface == surface;
			});

			if (!used) {
				return surface;
			}
		}

		return -1;
	}
}
