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

#include "media/video/codec/linux/H264Parser.h"

#include "rtc_base/bitstream_reader.h"

#include <algorithm>

namespace jni
{
	namespace
	{
		constexpr uint32_t kMaxSpsCount = 32;
		constexpr uint32_t kMaxPpsCount = 256;

		// The default scaling lists (tables 7-3 and 7-4), in coded order.
		constexpr std::array<uint8_t, 16> kDefault4x4Intra = {
			6, 13, 13, 20, 20, 20, 28, 28, 28, 28, 32, 32, 32, 37, 37, 42
		};
		constexpr std::array<uint8_t, 16> kDefault4x4Inter = {
			10, 14, 14, 20, 20, 20, 24, 24, 24, 24, 27, 27, 27, 30, 30, 34
		};
		constexpr std::array<uint8_t, 64> kDefault8x8Intra = {
			6, 10, 10, 13, 11, 13, 16, 16, 16, 16, 18, 18, 18, 18, 18, 23,
			23, 23, 23, 23, 23, 25, 25, 25, 25, 25, 25, 25, 27, 27, 27, 27,
			27, 27, 27, 27, 29, 29, 29, 29, 29, 29, 29, 31, 31, 31, 31, 31,
			31, 33, 33, 33, 33, 33, 36, 36, 36, 36, 38, 38, 38, 40, 40, 42
		};
		constexpr std::array<uint8_t, 64> kDefault8x8Inter = {
			9, 13, 13, 15, 13, 15, 17, 17, 17, 17, 19, 19, 19, 19, 19, 21,
			21, 21, 21, 21, 21, 22, 22, 22, 22, 22, 22, 22, 24, 24, 24, 24,
			24, 24, 24, 24, 25, 25, 25, 25, 25, 25, 25, 27, 27, 27, 27, 27,
			27, 28, 28, 28, 28, 28, 30, 30, 30, 30, 32, 32, 32, 33, 33, 35
		};

		// The profiles whose SPS has chroma format, bit depth and scaling
		// matrices.
		bool HasHighProfileFields(uint32_t profile)
		{
			switch (profile) {
				case 100: case 110: case 122: case 244: case 44: case 83: case 86: case 118: case 128: case 138:
				case 139: case 134: case 135:
					return true;
				default:
					return false;
			}
		}

		// The RBSP of a NAL unit, without its one byte header.
		std::vector<uint8_t> Unescape(std::span<const uint8_t> nal)
		{
			std::vector<uint8_t> rbsp;
			rbsp.reserve(nal.size());

			int zeros = 0;

			for (size_t i = 1; i < nal.size(); i++) {
				if (zeros >= 2 && nal[i] == 3) {
					zeros = 0;
					continue;
				}

				rbsp.push_back(nal[i]);
				zeros = nal[i] == 0 ? zeros + 1 : 0;
			}

			return rbsp;
		}

		int ConsumedBits(const webrtc::BitstreamReader & reader, size_t size)
		{
			return static_cast<int>(size * 8) - reader.RemainingBitCount();
		}

		// scaling_list() (7.3.2.1.1.1); returns whether the default list is
		// to be used.
		template <size_t Size>
		bool ParseScalingList(webrtc::BitstreamReader & reader, std::array<uint8_t, Size> & list)
		{
			int32_t lastScale = 8;
			int32_t nextScale = 8;

			for (size_t j = 0; j < Size; j++) {
				if (nextScale != 0) {
					const int32_t delta = reader.ReadSignedExponentialGolomb();

					nextScale = (lastScale + delta + 256) % 256;

					if (j == 0 && nextScale == 0) {
						return true;
					}
				}

				list[j] = static_cast<uint8_t>(nextScale == 0 ? lastScale : nextScale);
				lastScale = list[j];
			}

			return false;
		}

		// The lists of a set as coded, where present, or by fall-back rule A
		// (table 7-2) or B, given the lists rule B falls back to.
		void ResolveScalingLists(const std::array<bool, 8> & present, const std::array<bool, 8> & useDefault,
			const H264ScalingLists & coded, const H264ScalingLists * fallback, H264ScalingLists & lists)
		{
			for (int i = 0; i < 6; i++) {
				const auto & defaultList = i < 3 ? kDefault4x4Intra : kDefault4x4Inter;

				if (present[i]) {
					lists.list4x4[i] = useDefault[i] ? defaultList : coded.list4x4[i];
				}
				else if (i == 0 || i == 3) {
					lists.list4x4[i] = fallback != nullptr ? fallback->list4x4[i] : defaultList;
				}
				else {
					lists.list4x4[i] = lists.list4x4[i - 1];
				}
			}

			for (int i = 0; i < 2; i++) {
				const auto & defaultList = i == 0 ? kDefault8x8Intra : kDefault8x8Inter;

				if (present[6 + i]) {
					lists.list8x8[i] = useDefault[6 + i] ? defaultList : coded.list8x8[i];
				}
				else {
					lists.list8x8[i] = fallback != nullptr ? fallback->list8x8[i] : defaultList;
				}
			}
		}

		// The scaling lists of a set: whether each is present, whether its
		// default is to be used, and the lists coded.
		void ParseScalingLists(webrtc::BitstreamReader & reader, int count, std::array<bool, 8> & present,
			std::array<bool, 8> & useDefault, H264ScalingLists & coded)
		{
			for (int i = 0; i < count; i++) {
				present[i] = reader.Read<bool>();

				if (!present[i]) {
					continue;
				}

				useDefault[i] = i < 6 ? ParseScalingList(reader, coded.list4x4[i])
					: ParseScalingList(reader, coded.list8x8[i - 6]);
			}
		}

		void SkipHrdParameters(webrtc::BitstreamReader & reader)
		{
			const uint32_t cpbCount = reader.ReadExponentialGolomb() + 1;

			if (cpbCount > 32) {
				reader.Invalidate();
				return;
			}

			// bit_rate_scale and cpb_size_scale.
			reader.ConsumeBits(8);

			for (uint32_t i = 0; i < cpbCount; i++) {
				reader.ReadExponentialGolomb();
				reader.ReadExponentialGolomb();
				reader.ConsumeBits(1);
			}

			// The lengths of the delays and of the time offset.
			reader.ConsumeBits(20);
		}

		// vui_parameters() (E.1.1), for the reordering it allows.
		void ParseVui(webrtc::BitstreamReader & reader, H264Sps & sps)
		{
			if (reader.Read<bool>()) {
				if (reader.ReadBits(8) == 255) {
					reader.ConsumeBits(32);
				}
			}
			if (reader.Read<bool>()) {
				reader.ConsumeBits(1);
			}
			if (reader.Read<bool>()) {
				reader.ConsumeBits(4);

				if (reader.Read<bool>()) {
					reader.ConsumeBits(24);
				}
			}
			if (reader.Read<bool>()) {
				reader.ReadExponentialGolomb();
				reader.ReadExponentialGolomb();
			}
			if (reader.Read<bool>()) {
				reader.ConsumeBits(65);
			}

			const bool nalHrd = reader.Read<bool>();

			if (nalHrd) {
				SkipHrdParameters(reader);
			}

			const bool vclHrd = reader.Read<bool>();

			if (vclHrd) {
				SkipHrdParameters(reader);
			}
			if (nalHrd || vclHrd) {
				reader.ConsumeBits(1);
			}

			// pic_struct_present_flag.
			reader.ConsumeBits(1);

			if (reader.Read<bool>()) {
				reader.ConsumeBits(1);

				for (int i = 0; i < 4; i++) {
					reader.ReadExponentialGolomb();
				}

				const uint32_t maxNumReorderFrames = reader.ReadExponentialGolomb();
				// max_dec_frame_buffering.
				reader.ReadExponentialGolomb();

				if (reader.Ok()) {
					sps.maxNumReorderFrames = maxNumReorderFrames;
				}
			}
		}

		// pred_weight_table() (7.3.3.2) of one list.
		void ParseWeights(webrtc::BitstreamReader & reader, H264SliceHeader & header, int list, uint32_t count)
		{
			for (uint32_t i = 0; i < count; i++) {
				header.lumaWeight[list][i] = 1 << header.lumaLog2WeightDenom;
				header.lumaOffset[list][i] = 0;

				if (reader.Read<bool>()) {
					header.lumaWeightsGiven[list] = true;
					header.lumaWeight[list][i] = reader.ReadSignedExponentialGolomb();
					header.lumaOffset[list][i] = reader.ReadSignedExponentialGolomb();
				}

				const bool chroma = reader.Read<bool>();

				for (int j = 0; j < 2; j++) {
					header.chromaWeight[list][i][j] = 1 << header.chromaLog2WeightDenom;
					header.chromaOffset[list][i][j] = 0;

					if (chroma) {
						header.chromaWeightsGiven[list] = true;
						header.chromaWeight[list][i][j] = reader.ReadSignedExponentialGolomb();
						header.chromaOffset[list][i][j] = reader.ReadSignedExponentialGolomb();
					}
				}
			}
		}

		// ref_pic_list_modification() (7.3.3.1) of one list.
		bool ParseListModification(webrtc::BitstreamReader & reader, std::vector<H264Operation> & operations)
		{
			if (!reader.Read<bool>()) {
				return true;
			}

			while (true) {
				H264Operation operation;
				operation.operation = reader.ReadExponentialGolomb();

				if (operation.operation == 3) {
					return true;
				}
				if (operation.operation > 3 || operations.size() > H264SliceHeader::kMaxRefs || !reader.Ok()) {
					return false;
				}

				operation.value = reader.ReadExponentialGolomb();
				operations.push_back(operation);
			}
		}

		// dec_ref_pic_marking() (7.3.3.3).
		bool ParseRefPicMarking(webrtc::BitstreamReader & reader, H264SliceHeader & header)
		{
			if (header.IsIdr()) {
				header.noOutputOfPriorPics = reader.Read<bool>();
				header.longTermReference = reader.Read<bool>();

				return true;
			}

			header.adaptiveRefPicMarking = reader.Read<bool>();

			if (!header.adaptiveRefPicMarking) {
				return true;
			}

			while (true) {
				H264Operation operation;
				operation.operation = reader.ReadExponentialGolomb();

				if (operation.operation == 0) {
					return true;
				}
				if (operation.operation > 6 || header.memoryManagement.size() > 66 || !reader.Ok()) {
					return false;
				}

				if (operation.operation == 1 || operation.operation == 2 || operation.operation == 3
					|| operation.operation == 4)
				{
					operation.value = reader.ReadExponentialGolomb();
				}
				if (operation.operation == 3 || operation.operation == 6) {
					operation.longTermFrameIdx = reader.ReadExponentialGolomb();
				}

				header.memoryManagement.push_back(operation);
			}
		}
	}

	uint32_t H264Sps::MaxFrameNum() const
	{
		return 1u << (log2MaxFrameNumMinus4 + 4);
	}

	bool H264SliceHeader::IsIdr() const
	{
		return nalType == H264Parser::kNalIdr;
	}

	bool H264SliceHeader::IsIntra() const
	{
		return sliceType == H264Parser::kSliceI;
	}

	bool H264SliceHeader::IsB() const
	{
		return sliceType == H264Parser::kSliceB;
	}

	bool H264Parser::ParseSps(std::span<const uint8_t> nal)
	{
		const std::vector<uint8_t> rbsp = Unescape(nal);
		webrtc::BitstreamReader reader(rbsp);
		H264Sps sps;

		sps.profileIdc = reader.ReadBits(8);
		// The constraint flags.
		reader.ConsumeBits(8);
		sps.levelIdc = reader.ReadBits(8);

		const uint32_t id = reader.ReadExponentialGolomb();

		if (id >= kMaxSpsCount) {
			return false;
		}

		if (HasHighProfileFields(sps.profileIdc)) {
			sps.chromaFormatIdc = reader.ReadExponentialGolomb();

			if (sps.chromaFormatIdc == 3) {
				return false;
			}

			sps.bitDepthLumaMinus8 = reader.ReadExponentialGolomb();
			sps.bitDepthChromaMinus8 = reader.ReadExponentialGolomb();
			// qpprime_y_zero_transform_bypass_flag.
			reader.ConsumeBits(1);
			sps.scalingMatrixPresent = reader.Read<bool>();

			if (sps.scalingMatrixPresent) {
				std::array<bool, 8> present = {};
				std::array<bool, 8> useDefault = {};
				H264ScalingLists coded;

				ParseScalingLists(reader, 8, present, useDefault, coded);
				ResolveScalingLists(present, useDefault, coded, nullptr, sps.scalingLists);
			}
		}

		sps.log2MaxFrameNumMinus4 = reader.ReadExponentialGolomb();
		sps.pocType = reader.ReadExponentialGolomb();

		if (sps.log2MaxFrameNumMinus4 > 12 || sps.pocType > 2) {
			return false;
		}

		if (sps.pocType == 0) {
			sps.log2MaxPocLsbMinus4 = reader.ReadExponentialGolomb();

			if (sps.log2MaxPocLsbMinus4 > 12) {
				return false;
			}
		}
		else if (sps.pocType == 1) {
			sps.deltaPicOrderAlwaysZero = reader.Read<bool>();
			sps.offsetForNonRefPic = reader.ReadSignedExponentialGolomb();
			sps.offsetForTopToBottomField = reader.ReadSignedExponentialGolomb();

			const uint32_t cycleLength = reader.ReadExponentialGolomb();

			if (cycleLength > 255) {
				return false;
			}

			for (uint32_t i = 0; i < cycleLength; i++) {
				sps.offsetForRefFrame.push_back(reader.ReadSignedExponentialGolomb());
			}
		}

		sps.maxNumRefFrames = reader.ReadExponentialGolomb();
		sps.gapsInFrameNumAllowed = reader.Read<bool>();
		sps.widthInMbs = reader.ReadExponentialGolomb() + 1;
		sps.heightInMbs = reader.ReadExponentialGolomb() + 1;
		sps.frameMbsOnly = reader.Read<bool>();

		// Interlaced streams are left to the software decoder.
		if (!sps.frameMbsOnly || sps.maxNumRefFrames > 16 || sps.widthInMbs > 1024 || sps.heightInMbs > 1024) {
			return false;
		}

		sps.direct8x8Inference = reader.Read<bool>();

		if (reader.Read<bool>()) {
			// In units of two luma samples for 4:2:0 frames.
			const uint32_t unitX = sps.chromaFormatIdc == 0 ? 1 : 2;
			const uint32_t unitY = sps.chromaFormatIdc == 1 ? 2 : 1;

			sps.cropLeft = reader.ReadExponentialGolomb() * unitX;
			sps.cropRight = reader.ReadExponentialGolomb() * unitX;
			sps.cropTop = reader.ReadExponentialGolomb() * unitY;
			sps.cropBottom = reader.ReadExponentialGolomb() * unitY;
		}

		if (!reader.Ok() || sps.chromaFormatIdc != 1 || sps.bitDepthLumaMinus8 != 0 || sps.bitDepthChromaMinus8 != 0
			|| sps.cropLeft + sps.cropRight >= sps.widthInMbs * 16
			|| sps.cropTop + sps.cropBottom >= sps.heightInMbs * 16)
		{
			return false;
		}

		if (reader.Read<bool>()) {
			// A VUI that cannot be read leaves the reordering unknown.
			webrtc::BitstreamReader vui = reader;
			ParseVui(vui, sps);

			if (!vui.Ok()) {
				sps.maxNumReorderFrames.reset();
			}
		}

		spss[id] = std::move(sps);

		return true;
	}

	bool H264Parser::ParsePps(std::span<const uint8_t> nal)
	{
		const std::vector<uint8_t> rbsp = Unescape(nal);
		webrtc::BitstreamReader reader(rbsp);
		H264Pps pps;

		const uint32_t id = reader.ReadExponentialGolomb();
		pps.spsId = reader.ReadExponentialGolomb();

		if (id >= kMaxPpsCount || pps.spsId >= kMaxSpsCount) {
			return false;
		}

		pps.entropyCodingMode = reader.Read<bool>();
		pps.bottomFieldPicOrderInFramePresent = reader.Read<bool>();
		pps.numSliceGroupsMinus1 = reader.ReadExponentialGolomb();

		// Slice groups are left to the software decoder.
		if (pps.numSliceGroupsMinus1 > 0) {
			return false;
		}

		pps.numRefIdxL0DefaultMinus1 = reader.ReadExponentialGolomb();
		pps.numRefIdxL1DefaultMinus1 = reader.ReadExponentialGolomb();
		pps.weightedPred = reader.Read<bool>();
		pps.weightedBipredIdc = reader.ReadBits(2);
		pps.picInitQpMinus26 = reader.ReadSignedExponentialGolomb();
		pps.picInitQsMinus26 = reader.ReadSignedExponentialGolomb();
		pps.chromaQpIndexOffset = reader.ReadSignedExponentialGolomb();
		pps.deblockingFilterControlPresent = reader.Read<bool>();
		pps.constrainedIntraPred = reader.Read<bool>();
		pps.redundantPicCntPresent = reader.Read<bool>();
		pps.secondChromaQpIndexOffset = pps.chromaQpIndexOffset;

		if (pps.numRefIdxL0DefaultMinus1 > 31 || pps.numRefIdxL1DefaultMinus1 > 31 || pps.weightedBipredIdc > 2) {
			return false;
		}

		// more_rbsp_data(): anything before the stop bit, the last one bit.
		size_t lastOne = 0;

		for (size_t i = rbsp.size(); i > 0; i--) {
			if (rbsp[i - 1] != 0) {
				int bit = 0;

				while (((rbsp[i - 1] >> bit) & 1) == 0) {
					bit++;
				}

				lastOne = (i - 1) * 8 + (7 - bit);
				break;
			}
		}

		if (reader.Ok() && static_cast<size_t>(ConsumedBits(reader, rbsp.size())) < lastOne) {
			pps.transform8x8Mode = reader.Read<bool>();
			pps.scalingMatrixPresent = reader.Read<bool>();

			if (pps.scalingMatrixPresent) {
				ParseScalingLists(reader, pps.transform8x8Mode ? 8 : 6, pps.scalingListPresent,
					pps.useDefaultScalingList, pps.scalingLists);
			}

			pps.secondChromaQpIndexOffset = reader.ReadSignedExponentialGolomb();
		}

		if (!reader.Ok()) {
			return false;
		}

		ppss[id] = std::move(pps);

		return true;
	}

	std::optional<H264SliceHeader> H264Parser::ParseSliceHeader(std::span<const uint8_t> nal) const
	{
		if (nal.size() < 2) {
			return std::nullopt;
		}

		const std::vector<uint8_t> rbsp = Unescape(nal);
		webrtc::BitstreamReader reader(rbsp);
		H264SliceHeader header;

		header.nalType = nal[0] & 0x1F;
		header.nalRefIdc = (nal[0] >> 5) & 0x03;
		header.firstMbInSlice = reader.ReadExponentialGolomb();

		const uint32_t sliceType = reader.ReadExponentialGolomb();

		// SP and SI slices are left to the software decoder.
		if (sliceType > 9 || sliceType % 5 > kSliceI) {
			return std::nullopt;
		}

		header.sliceType = sliceType % 5;
		header.ppsId = reader.ReadExponentialGolomb();

		const H264Pps * pps = Pps(header.ppsId);
		const H264Sps * sps = pps != nullptr ? Sps(pps->spsId) : nullptr;

		if (sps == nullptr) {
			return std::nullopt;
		}

		header.frameNum = static_cast<uint32_t>(reader.ReadBits(static_cast<int>(sps->log2MaxFrameNumMinus4) + 4));

		if (header.IsIdr()) {
			header.idrPicId = reader.ReadExponentialGolomb();
		}

		if (sps->pocType == 0) {
			header.pocLsb = static_cast<uint32_t>(reader.ReadBits(static_cast<int>(sps->log2MaxPocLsbMinus4) + 4));

			if (pps->bottomFieldPicOrderInFramePresent) {
				header.deltaPocBottom = reader.ReadSignedExponentialGolomb();
			}
		}
		else if (sps->pocType == 1 && !sps->deltaPicOrderAlwaysZero) {
			header.deltaPoc[0] = reader.ReadSignedExponentialGolomb();

			if (pps->bottomFieldPicOrderInFramePresent) {
				header.deltaPoc[1] = reader.ReadSignedExponentialGolomb();
			}
		}

		if (pps->redundantPicCntPresent) {
			header.redundantPicCnt = reader.ReadExponentialGolomb();
		}

		if (header.IsB()) {
			header.directSpatialMvPred = reader.Read<bool>();
		}

		if (!header.IsIntra()) {
			header.numRefIdxL0Minus1 = pps->numRefIdxL0DefaultMinus1;
			header.numRefIdxL1Minus1 = pps->numRefIdxL1DefaultMinus1;

			if (reader.Read<bool>()) {
				header.numRefIdxL0Minus1 = reader.ReadExponentialGolomb();

				if (header.IsB()) {
					header.numRefIdxL1Minus1 = reader.ReadExponentialGolomb();
				}
			}

			// 32 references are for fields, which are not taken.
			if (header.numRefIdxL0Minus1 > 15 || header.numRefIdxL1Minus1 > 15) {
				return std::nullopt;
			}

			if (!ParseListModification(reader, header.listModifications[0])
				|| (header.IsB() && !ParseListModification(reader, header.listModifications[1])))
			{
				return std::nullopt;
			}

			if ((pps->weightedPred && header.sliceType == kSliceP) || (pps->weightedBipredIdc == 1 && header.IsB())) {
				header.lumaLog2WeightDenom = reader.ReadExponentialGolomb();
				header.chromaLog2WeightDenom = reader.ReadExponentialGolomb();

				if (header.lumaLog2WeightDenom > 7 || header.chromaLog2WeightDenom > 7) {
					return std::nullopt;
				}

				ParseWeights(reader, header, 0, header.numRefIdxL0Minus1 + 1);

				if (header.IsB()) {
					ParseWeights(reader, header, 1, header.numRefIdxL1Minus1 + 1);
				}
			}
		}

		if (header.nalRefIdc != 0 && !ParseRefPicMarking(reader, header)) {
			return std::nullopt;
		}

		if (pps->entropyCodingMode && !header.IsIntra()) {
			header.cabacInitIdc = reader.ReadExponentialGolomb();

			if (header.cabacInitIdc > 2) {
				return std::nullopt;
			}
		}

		header.qpDelta = reader.ReadSignedExponentialGolomb();

		if (pps->deblockingFilterControlPresent) {
			header.disableDeblockingFilterIdc = reader.ReadExponentialGolomb();

			if (header.disableDeblockingFilterIdc > 2) {
				return std::nullopt;
			}

			if (header.disableDeblockingFilterIdc != 1) {
				header.alphaOffsetDiv2 = reader.ReadSignedExponentialGolomb();
				header.betaOffsetDiv2 = reader.ReadSignedExponentialGolomb();
			}
		}

		if (!reader.Ok()) {
			return std::nullopt;
		}

		// With the header byte.
		header.dataBitOffset = static_cast<uint32_t>(ConsumedBits(reader, rbsp.size()) + 8);

		return header;
	}

	const H264Sps * H264Parser::Sps(uint32_t id) const
	{
		auto sps = spss.find(id);

		return sps != spss.end() ? &sps->second : nullptr;
	}

	const H264Pps * H264Parser::Pps(uint32_t id) const
	{
		auto pps = ppss.find(id);

		return pps != ppss.end() ? &pps->second : nullptr;
	}

	H264ScalingLists H264Parser::ScalingLists(const H264Sps & sps, const H264Pps & pps)
	{
		H264ScalingLists lists;

		if (pps.scalingMatrixPresent) {
			// Fall-back rule B to the lists of the SPS, or rule A where it has
			// none.
			ResolveScalingLists(pps.scalingListPresent, pps.useDefaultScalingList, pps.scalingLists,
				sps.scalingMatrixPresent ? &sps.scalingLists : nullptr, lists);
		}
		else if (sps.scalingMatrixPresent) {
			lists = sps.scalingLists;
		}
		else {
			for (auto & list : lists.list4x4) {
				list.fill(16);
			}
			for (auto & list : lists.list8x8) {
				list.fill(16);
			}
		}

		return lists;
	}

	void H264Parser::Reset()
	{
		spss.clear();
		ppss.clear();
	}
}
