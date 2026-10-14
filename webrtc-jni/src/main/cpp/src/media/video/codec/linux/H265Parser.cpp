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

#include "media/video/codec/linux/H265Parser.h"

#include "common_video/h265/h265_sps_parser.h"
#include "rtc_base/bitstream_reader.h"

#include <algorithm>

namespace jni
{
	namespace
	{
		constexpr uint32_t kMaxSpsCount = 16;
		constexpr uint32_t kMaxPpsCount = 64;
		constexpr uint32_t kMaxShortTermRpsCount = 64;
		constexpr uint32_t kMaxLongTermRefsSps = 32;
		constexpr uint32_t kMaxTiles = 20;

		// Main and Main Still Picture.
		constexpr uint32_t kProfileMain = 1;
		constexpr uint32_t kProfileMainStill = 3;

		// A NAL unit without its two byte header and without emulation
		// prevention bytes, with where those were.
		struct Rbsp
		{
			std::vector<uint8_t> bytes;
			// The positions in the NAL unit of the bytes removed.
			std::vector<size_t> removed;
		};

		Rbsp Unescape(std::span<const uint8_t> nal)
		{
			Rbsp rbsp;
			rbsp.bytes.reserve(nal.size());

			int zeros = 0;

			for (size_t i = 2; i < nal.size(); i++) {
				if (zeros >= 2 && nal[i] == 3) {
					rbsp.removed.push_back(i);
					zeros = 0;
					continue;
				}

				rbsp.bytes.push_back(nal[i]);
				zeros = nal[i] == 0 ? zeros + 1 : 0;
			}

			return rbsp;
		}

		// The position in the NAL unit of a byte of its RBSP.
		size_t NalOffset(const Rbsp & rbsp, size_t rbspOffset)
		{
			size_t offset = rbspOffset + 2;

			for (size_t position : rbsp.removed) {
				if (position <= offset) {
					offset++;
				}
			}

			return offset;
		}

		// Ceil(Log2(value)).
		int CeilLog2(uint32_t value)
		{
			int bits = 0;

			while ((1u << bits) < value) {
				bits++;
			}

			return bits;
		}

		int ConsumedBits(const webrtc::BitstreamReader & reader, size_t size)
		{
			return static_cast<int>(size * 8) - reader.RemainingBitCount();
		}

		void SkipProfileTierLevel(webrtc::BitstreamReader & reader, uint32_t maxSubLayersMinus1, H265Sps & sps)
		{
			// general_profile_space, general_tier_flag.
			reader.ConsumeBits(3);
			sps.generalProfileIdc = reader.ReadBits(5);
			sps.generalProfileCompatibility = static_cast<uint32_t>(reader.ReadBits(32));
			// The source and constraint flags, and general_level_idc.
			reader.ConsumeBits(48);
			reader.ConsumeBits(8);

			bool profilePresent[8] = {};
			bool levelPresent[8] = {};

			for (uint32_t i = 0; i < maxSubLayersMinus1; i++) {
				profilePresent[i] = reader.Read<bool>();
				levelPresent[i] = reader.Read<bool>();
			}

			if (maxSubLayersMinus1 > 0) {
				reader.ConsumeBits(2 * (8 - static_cast<int>(maxSubLayersMinus1)));
			}

			for (uint32_t i = 0; i < maxSubLayersMinus1; i++) {
				if (profilePresent[i]) {
					reader.ConsumeBits(88);
				}
				if (levelPresent[i]) {
					reader.ConsumeBits(8);
				}
			}
		}

		// st_ref_pic_set(index) (7.3.7), with the derivation of an inter
		// predicted set (7.4.8).
		bool ParseShortTermRps(webrtc::BitstreamReader & reader, uint32_t index,
			const std::vector<H265ShortTermRps> & sets, H265ShortTermRps & rps)
		{
			const uint32_t count = static_cast<uint32_t>(sets.size());
			bool interPrediction = false;

			if (index != 0) {
				interPrediction = reader.Read<bool>();
			}

			if (interPrediction) {
				uint32_t deltaIdxMinus1 = 0;

				if (index == count) {
					deltaIdxMinus1 = reader.ReadExponentialGolomb();
				}
				if (deltaIdxMinus1 + 1 > index) {
					return false;
				}

				const H265ShortTermRps & reference = sets[index - (deltaIdxMinus1 + 1)];
				const bool sign = reader.Read<bool>();
				const uint32_t absDeltaMinus1 = reader.ReadExponentialGolomb();

				if (absDeltaMinus1 > 0x7FFF) {
					return false;
				}

				const int32_t deltaRps = (sign ? -1 : 1) * static_cast<int32_t>(absDeltaMinus1 + 1);
				const int numDeltaPocs = reference.numNegative + reference.numPositive;

				bool usedByCurr[H265ShortTermRps::kMaxPictures * 2 + 1] = {};
				bool useDelta[H265ShortTermRps::kMaxPictures * 2 + 1] = {};

				for (int j = 0; j <= numDeltaPocs; j++) {
					usedByCurr[j] = reader.Read<bool>();
					useDelta[j] = usedByCurr[j] ? true : reader.Read<bool>();
				}

				const auto add = [](int & count, std::array<int32_t, H265ShortTermRps::kMaxPictures> & deltas,
					std::array<bool, H265ShortTermRps::kMaxPictures> & used, int32_t delta, bool isUsed)
				{
					if (count >= H265ShortTermRps::kMaxPictures) {
						return false;
					}

					deltas[count] = delta;
					used[count] = isUsed;
					count++;

					return true;
				};

				int i = 0;
				bool ok = true;

				for (int j = reference.numPositive - 1; j >= 0; j--) {
					const int32_t dPoc = reference.deltaPocS1[j] + deltaRps;

					if (dPoc < 0 && useDelta[reference.numNegative + j]) {
						ok &= add(i, rps.deltaPocS0, rps.usedS0, dPoc, usedByCurr[reference.numNegative + j]);
					}
				}
				if (deltaRps < 0 && useDelta[numDeltaPocs]) {
					ok &= add(i, rps.deltaPocS0, rps.usedS0, deltaRps, usedByCurr[numDeltaPocs]);
				}
				for (int j = 0; j < reference.numNegative; j++) {
					const int32_t dPoc = reference.deltaPocS0[j] + deltaRps;

					if (dPoc < 0 && useDelta[j]) {
						ok &= add(i, rps.deltaPocS0, rps.usedS0, dPoc, usedByCurr[j]);
					}
				}

				rps.numNegative = i;
				i = 0;

				for (int j = reference.numNegative - 1; j >= 0; j--) {
					const int32_t dPoc = reference.deltaPocS0[j] + deltaRps;

					if (dPoc > 0 && useDelta[j]) {
						ok &= add(i, rps.deltaPocS1, rps.usedS1, dPoc, usedByCurr[j]);
					}
				}
				if (deltaRps > 0 && useDelta[numDeltaPocs]) {
					ok &= add(i, rps.deltaPocS1, rps.usedS1, deltaRps, usedByCurr[numDeltaPocs]);
				}
				for (int j = 0; j < reference.numPositive; j++) {
					const int32_t dPoc = reference.deltaPocS1[j] + deltaRps;

					if (dPoc > 0 && useDelta[reference.numNegative + j]) {
						ok &= add(i, rps.deltaPocS1, rps.usedS1, dPoc, usedByCurr[reference.numNegative + j]);
					}
				}

				rps.numPositive = i;

				return ok && reader.Ok();
			}

			const uint32_t numNegative = reader.ReadExponentialGolomb();
			const uint32_t numPositive = reader.ReadExponentialGolomb();

			if (numNegative > H265ShortTermRps::kMaxPictures || numPositive > H265ShortTermRps::kMaxPictures
				|| numNegative + numPositive > H265ShortTermRps::kMaxPictures)
			{
				return false;
			}

			rps.numNegative = static_cast<int>(numNegative);
			rps.numPositive = static_cast<int>(numPositive);

			int32_t poc = 0;

			for (uint32_t i = 0; i < numNegative; i++) {
				const uint32_t deltaMinus1 = reader.ReadExponentialGolomb();

				if (deltaMinus1 > 0x7FFF) {
					return false;
				}

				poc -= static_cast<int32_t>(deltaMinus1) + 1;
				rps.deltaPocS0[i] = poc;
				rps.usedS0[i] = reader.Read<bool>();
			}

			poc = 0;

			for (uint32_t i = 0; i < numPositive; i++) {
				const uint32_t deltaMinus1 = reader.ReadExponentialGolomb();

				if (deltaMinus1 > 0x7FFF) {
					return false;
				}

				poc += static_cast<int32_t>(deltaMinus1) + 1;
				rps.deltaPocS1[i] = poc;
				rps.usedS1[i] = reader.Read<bool>();
			}

			return reader.Ok();
		}

		// pred_weight_table() (7.3.6.3) of one list, with the chroma offsets
		// derived (7-56) for 8 bit samples.
		void ParseWeights(webrtc::BitstreamReader & reader, H265SliceHeader & header, int list, uint32_t count,
			bool chroma)
		{
			const uint32_t chromaLog2Denom = static_cast<uint32_t>(
				static_cast<int32_t>(header.lumaLog2WeightDenom) + header.deltaChromaLog2WeightDenom);
			constexpr int32_t kOffsetHalfRange = 1 << 7;

			bool lumaFlags[H265SliceHeader::kMaxRefs] = {};
			bool chromaFlags[H265SliceHeader::kMaxRefs] = {};

			for (uint32_t i = 0; i < count; i++) {
				lumaFlags[i] = reader.Read<bool>();
			}
			if (chroma) {
				for (uint32_t i = 0; i < count; i++) {
					chromaFlags[i] = reader.Read<bool>();
				}
			}

			for (uint32_t i = 0; i < count; i++) {
				if (lumaFlags[i]) {
					header.deltaLumaWeight[list][i] = reader.ReadSignedExponentialGolomb();
					header.lumaOffset[list][i] = reader.ReadSignedExponentialGolomb();
				}
				if (chromaFlags[i]) {
					for (int j = 0; j < 2; j++) {
						const int32_t deltaWeight = reader.ReadSignedExponentialGolomb();
						const int32_t deltaOffset = reader.ReadSignedExponentialGolomb();
						const int32_t weight = (1 << chromaLog2Denom) + deltaWeight;
						const int32_t offset = kOffsetHalfRange
							- ((kOffsetHalfRange * weight) >> chromaLog2Denom) + deltaOffset;

						header.deltaChromaWeight[list][i][j] = deltaWeight;
						header.chromaOffset[list][i][j] = std::clamp(offset, -kOffsetHalfRange, kOffsetHalfRange - 1);
					}
				}
			}
		}
	}

	uint32_t H265Sps::Log2CtbSize() const
	{
		return log2MinCbMinus3 + 3 + log2DiffMaxMinCb;
	}

	uint32_t H265Sps::WidthInCtbs() const
	{
		const uint32_t ctbSize = 1u << Log2CtbSize();

		return (width + ctbSize - 1) / ctbSize;
	}

	uint32_t H265Sps::HeightInCtbs() const
	{
		const uint32_t ctbSize = 1u << Log2CtbSize();

		return (height + ctbSize - 1) / ctbSize;
	}

	bool H265SliceHeader::IsIntra() const
	{
		return sliceType == H265Parser::kSliceI;
	}

	bool H265SliceHeader::IsB() const
	{
		return sliceType == H265Parser::kSliceB;
	}

	uint32_t H265SliceHeader::NumPicTotalCurr() const
	{
		uint32_t count = 0;

		for (int i = 0; i < shortTermRps.numNegative; i++) {
			count += shortTermRps.usedS0[i] ? 1 : 0;
		}
		for (int i = 0; i < shortTermRps.numPositive; i++) {
			count += shortTermRps.usedS1[i] ? 1 : 0;
		}
		for (bool used : longTermUsed) {
			count += used ? 1 : 0;
		}

		return count;
	}

	bool H265Parser::ParseSps(std::span<const uint8_t> nal)
	{
		const Rbsp rbsp = Unescape(nal);
		webrtc::BitstreamReader reader(rbsp.bytes);
		H265Sps sps;

		// sps_video_parameter_set_id.
		reader.ConsumeBits(4);
		const uint32_t maxSubLayersMinus1 = reader.ReadBits(3);
		// sps_temporal_id_nesting_flag.
		reader.ConsumeBits(1);

		if (maxSubLayersMinus1 > 6) {
			return false;
		}

		SkipProfileTierLevel(reader, maxSubLayersMinus1, sps);

		const uint32_t id = reader.ReadExponentialGolomb();

		if (id >= kMaxSpsCount) {
			return false;
		}

		sps.chromaFormatIdc = reader.ReadExponentialGolomb();

		if (sps.chromaFormatIdc == 3) {
			sps.separateColourPlane = reader.Read<bool>();
		}

		sps.width = reader.ReadExponentialGolomb();
		sps.height = reader.ReadExponentialGolomb();

		if (reader.Read<bool>()) {
			// In chroma samples, two luma samples each for 4:2:0.
			const uint32_t subWidth = sps.chromaFormatIdc == 1 || sps.chromaFormatIdc == 2 ? 2 : 1;
			const uint32_t subHeight = sps.chromaFormatIdc == 1 ? 2 : 1;

			sps.cropLeft = reader.ReadExponentialGolomb() * subWidth;
			sps.cropRight = reader.ReadExponentialGolomb() * subWidth;
			sps.cropTop = reader.ReadExponentialGolomb() * subHeight;
			sps.cropBottom = reader.ReadExponentialGolomb() * subHeight;
		}

		sps.bitDepthLumaMinus8 = reader.ReadExponentialGolomb();
		sps.bitDepthChromaMinus8 = reader.ReadExponentialGolomb();
		sps.log2MaxPocLsbMinus4 = reader.ReadExponentialGolomb();

		if (sps.log2MaxPocLsbMinus4 > 12) {
			return false;
		}

		const bool orderingInfoPresent = reader.Read<bool>();

		for (uint32_t i = orderingInfoPresent ? 0 : maxSubLayersMinus1; i <= maxSubLayersMinus1; i++) {
			sps.maxDecPicBufferingMinus1 = reader.ReadExponentialGolomb();
			sps.maxNumReorderPics = reader.ReadExponentialGolomb();
			// sps_max_latency_increase_plus1.
			reader.ReadExponentialGolomb();
		}

		if (sps.maxDecPicBufferingMinus1 > 15 || sps.maxNumReorderPics > sps.maxDecPicBufferingMinus1) {
			return false;
		}

		sps.log2MinCbMinus3 = reader.ReadExponentialGolomb();
		sps.log2DiffMaxMinCb = reader.ReadExponentialGolomb();
		sps.log2MinTbMinus2 = reader.ReadExponentialGolomb();
		sps.log2DiffMaxMinTb = reader.ReadExponentialGolomb();
		sps.maxTransformDepthInter = reader.ReadExponentialGolomb();
		sps.maxTransformDepthIntra = reader.ReadExponentialGolomb();

		if (sps.log2MinCbMinus3 + 3 + sps.log2DiffMaxMinCb > 6 || sps.log2MinTbMinus2 > 3
			|| sps.log2DiffMaxMinTb > 3)
		{
			return false;
		}

		sps.scalingListEnabled = reader.Read<bool>();

		if (sps.scalingListEnabled && reader.Read<bool>()
			&& !webrtc::H265SpsParser::ParseScalingListData(reader))
		{
			return false;
		}

		sps.amp = reader.Read<bool>();
		sps.sao = reader.Read<bool>();
		sps.pcm = reader.Read<bool>();

		if (sps.pcm) {
			sps.pcmBitDepthLumaMinus1 = reader.ReadBits(4);
			sps.pcmBitDepthChromaMinus1 = reader.ReadBits(4);
			sps.log2MinPcmCbMinus3 = reader.ReadExponentialGolomb();
			sps.log2DiffMaxMinPcmCb = reader.ReadExponentialGolomb();
			sps.pcmLoopFilterDisabled = reader.Read<bool>();
		}

		const uint32_t numShortTermRps = reader.ReadExponentialGolomb();

		if (numShortTermRps > kMaxShortTermRpsCount) {
			return false;
		}

		for (uint32_t i = 0; i < numShortTermRps; i++) {
			H265ShortTermRps rps;

			if (!ParseShortTermRps(reader, i, sps.shortTermRps, rps)) {
				return false;
			}

			sps.shortTermRps.push_back(rps);
		}

		sps.longTermRefsPresent = reader.Read<bool>();

		if (sps.longTermRefsPresent) {
			const uint32_t count = reader.ReadExponentialGolomb();

			if (count > kMaxLongTermRefsSps) {
				return false;
			}

			for (uint32_t i = 0; i < count; i++) {
				sps.ltRefPicPocLsb.push_back(static_cast<uint32_t>(reader.ReadBits(sps.log2MaxPocLsbMinus4 + 4)));
				sps.ltUsedByCurrPic.push_back(reader.Read<bool>());
			}
		}

		sps.temporalMvp = reader.Read<bool>();
		sps.strongIntraSmoothing = reader.Read<bool>();

		const bool main = sps.generalProfileIdc == kProfileMain || sps.generalProfileIdc == kProfileMainStill
			|| (sps.generalProfileCompatibility & (1u << (31 - kProfileMain))) != 0;

		if (!reader.Ok() || !main || sps.chromaFormatIdc != 1 || sps.bitDepthLumaMinus8 != 0
			|| sps.bitDepthChromaMinus8 != 0 || sps.width == 0 || sps.height == 0
			|| sps.cropLeft + sps.cropRight >= sps.width || sps.cropTop + sps.cropBottom >= sps.height)
		{
			return false;
		}

		spss[id] = std::move(sps);

		return true;
	}

	bool H265Parser::ParsePps(std::span<const uint8_t> nal)
	{
		const Rbsp rbsp = Unescape(nal);
		webrtc::BitstreamReader reader(rbsp.bytes);
		H265Pps pps;

		const uint32_t id = reader.ReadExponentialGolomb();
		pps.spsId = reader.ReadExponentialGolomb();

		if (id >= kMaxPpsCount || pps.spsId >= kMaxSpsCount) {
			return false;
		}

		pps.dependentSliceSegments = reader.Read<bool>();
		pps.outputFlagPresent = reader.Read<bool>();
		pps.numExtraSliceHeaderBits = reader.ReadBits(3);
		pps.signDataHiding = reader.Read<bool>();
		pps.cabacInitPresent = reader.Read<bool>();
		pps.numRefIdxL0DefaultMinus1 = reader.ReadExponentialGolomb();
		pps.numRefIdxL1DefaultMinus1 = reader.ReadExponentialGolomb();
		pps.initQpMinus26 = reader.ReadSignedExponentialGolomb();
		pps.constrainedIntraPred = reader.Read<bool>();
		pps.transformSkip = reader.Read<bool>();
		pps.cuQpDelta = reader.Read<bool>();

		if (pps.cuQpDelta) {
			pps.diffCuQpDeltaDepth = reader.ReadExponentialGolomb();
		}

		pps.cbQpOffset = reader.ReadSignedExponentialGolomb();
		pps.crQpOffset = reader.ReadSignedExponentialGolomb();
		pps.sliceChromaQpOffsetsPresent = reader.Read<bool>();
		pps.weightedPred = reader.Read<bool>();
		pps.weightedBipred = reader.Read<bool>();
		pps.transquantBypass = reader.Read<bool>();
		pps.tiles = reader.Read<bool>();
		pps.entropyCodingSync = reader.Read<bool>();

		if (pps.numRefIdxL0DefaultMinus1 > 14 || pps.numRefIdxL1DefaultMinus1 > 14) {
			return false;
		}

		if (pps.tiles) {
			pps.numTileColumnsMinus1 = reader.ReadExponentialGolomb();
			pps.numTileRowsMinus1 = reader.ReadExponentialGolomb();

			if (pps.numTileColumnsMinus1 >= kMaxTiles - 1 || pps.numTileRowsMinus1 >= kMaxTiles + 1) {
				return false;
			}

			pps.uniformSpacing = reader.Read<bool>();

			if (!pps.uniformSpacing) {
				for (uint32_t i = 0; i < pps.numTileColumnsMinus1; i++) {
					pps.columnWidthMinus1.push_back(reader.ReadExponentialGolomb());
				}
				for (uint32_t i = 0; i < pps.numTileRowsMinus1; i++) {
					pps.rowHeightMinus1.push_back(reader.ReadExponentialGolomb());
				}
			}

			pps.loopFilterAcrossTiles = reader.Read<bool>();
		}

		pps.loopFilterAcrossSlices = reader.Read<bool>();

		if (reader.Read<bool>()) {
			pps.deblockingFilterOverrideEnabled = reader.Read<bool>();
			pps.deblockingFilterDisabled = reader.Read<bool>();

			if (!pps.deblockingFilterDisabled) {
				pps.betaOffsetDiv2 = reader.ReadSignedExponentialGolomb();
				pps.tcOffsetDiv2 = reader.ReadSignedExponentialGolomb();
			}
		}

		pps.scalingListDataPresent = reader.Read<bool>();

		if (pps.scalingListDataPresent && !webrtc::H265SpsParser::ParseScalingListData(reader)) {
			return false;
		}

		pps.listsModificationPresent = reader.Read<bool>();
		pps.log2ParallelMergeLevelMinus2 = reader.ReadExponentialGolomb();
		pps.sliceHeaderExtensionPresent = reader.Read<bool>();
		pps.extensionPresent = reader.Read<bool>();

		if (!reader.Ok()) {
			return false;
		}

		ppss[id] = std::move(pps);

		return true;
	}

	std::optional<H265SliceHeader> H265Parser::ParseSliceHeader(std::span<const uint8_t> nal)
	{
		if (nal.size() < 3) {
			return std::nullopt;
		}

		const Rbsp rbsp = Unescape(nal);
		webrtc::BitstreamReader reader(rbsp.bytes);
		H265SliceHeader header;

		header.nalType = (nal[0] >> 1) & 0x3F;
		header.temporalId = static_cast<uint8_t>((nal[1] & 0x07) - 1);
		header.firstSliceInPicture = reader.Read<bool>();

		const bool irap = header.nalType >= kNalBlaWLp && header.nalType <= kNalReservedIrap23;
		const bool idr = header.nalType == kNalIdrWRadl || header.nalType == kNalIdrNLp;

		if (irap) {
			header.noOutputOfPriorPics = reader.Read<bool>();
		}

		header.ppsId = reader.ReadExponentialGolomb();

		const H265Pps * pps = Pps(header.ppsId);
		const H265Sps * sps = pps != nullptr ? Sps(pps->spsId) : nullptr;

		if (sps == nullptr) {
			return std::nullopt;
		}

		if (!header.firstSliceInPicture) {
			if (pps->dependentSliceSegments) {
				header.dependent = reader.Read<bool>();
			}

			header.segmentAddress = static_cast<uint32_t>(
				reader.ReadBits(CeilLog2(sps->WidthInCtbs() * sps->HeightInCtbs())));
		}

		if (header.dependent) {
			// The fields of the independent slice segment, but those of this
			// one's own parsed below.
			if (!lastIndependent || lastIndependent->ppsId != header.ppsId) {
				return std::nullopt;
			}

			const H265SliceHeader own = header;

			header = *lastIndependent;
			header.firstSliceInPicture = own.firstSliceInPicture;
			header.dependent = true;
			header.segmentAddress = own.segmentAddress;
			header.numEntryPoints = 0;
		}
		else {
			reader.ConsumeBits(static_cast<int>(pps->numExtraSliceHeaderBits));
			header.sliceType = reader.ReadExponentialGolomb();

			if (header.sliceType > kSliceI) {
				return std::nullopt;
			}

			if (pps->outputFlagPresent) {
				header.picOutput = reader.Read<bool>();
			}

			if (!idr) {
				const int log2MaxPocLsb = static_cast<int>(sps->log2MaxPocLsbMinus4) + 4;

				header.pocLsb = static_cast<uint32_t>(reader.ReadBits(log2MaxPocLsb));

				const bool fromSps = reader.Read<bool>();

				if (!fromSps) {
					const int before = ConsumedBits(reader, rbsp.bytes.size());

					if (!ParseShortTermRps(reader, static_cast<uint32_t>(sps->shortTermRps.size()), sps->shortTermRps,
						header.shortTermRps))
					{
						return std::nullopt;
					}

					header.shortTermRpsBits = static_cast<uint32_t>(ConsumedBits(reader, rbsp.bytes.size()) - before);
				}
				else {
					if (sps->shortTermRps.empty()) {
						return std::nullopt;
					}

					uint32_t index = 0;

					if (sps->shortTermRps.size() > 1) {
						index = static_cast<uint32_t>(
							reader.ReadBits(CeilLog2(static_cast<uint32_t>(sps->shortTermRps.size()))));
					}
					if (index >= sps->shortTermRps.size()) {
						return std::nullopt;
					}

					header.shortTermRps = sps->shortTermRps[index];
				}

				if (sps->longTermRefsPresent) {
					uint32_t numFromSps = 0;

					if (!sps->ltRefPicPocLsb.empty()) {
						numFromSps = reader.ReadExponentialGolomb();
					}

					const uint32_t numInHeader = reader.ReadExponentialGolomb();

					if (numFromSps > sps->ltRefPicPocLsb.size() || numFromSps + numInHeader > H265SliceHeader::kMaxRefs) {
						return std::nullopt;
					}

					const int32_t maxPocLsb = 1 << log2MaxPocLsb;
					int32_t msbCycle = 0;

					for (uint32_t i = 0; i < numFromSps + numInHeader; i++) {
						int32_t poc = 0;
						bool used = false;

						if (i < numFromSps) {
							uint32_t index = 0;

							if (sps->ltRefPicPocLsb.size() > 1) {
								index = static_cast<uint32_t>(
									reader.ReadBits(CeilLog2(static_cast<uint32_t>(sps->ltRefPicPocLsb.size()))));
							}
							if (index >= sps->ltRefPicPocLsb.size()) {
								return std::nullopt;
							}

							poc = static_cast<int32_t>(sps->ltRefPicPocLsb[index]);
							used = sps->ltUsedByCurrPic[index];
						}
						else {
							poc = static_cast<int32_t>(reader.ReadBits(log2MaxPocLsb));
							used = reader.Read<bool>();
						}

						const bool msbPresent = reader.Read<bool>();

						if (msbPresent) {
							const int32_t cycle = static_cast<int32_t>(reader.ReadExponentialGolomb());

							// DeltaPocMsbCycleLt (7-52) accumulates within each
							// of the two groups.
							msbCycle = (i == 0 || i == numFromSps) ? cycle : cycle + msbCycle;
						}
						else if (i == 0 || i == numFromSps) {
							msbCycle = 0;
						}

						// With the MSBs, the POC relative to the current
						// picture's MSBs, which the decoder adds.
						if (msbPresent) {
							poc -= msbCycle * maxPocLsb;
						}

						header.longTermPoc.push_back(poc);
						header.longTermMsbPresent.push_back(msbPresent);
						header.longTermUsed.push_back(used);
					}
				}

				if (sps->temporalMvp) {
					header.temporalMvp = reader.Read<bool>();
				}
			}

			if (sps->sao) {
				header.saoLuma = reader.Read<bool>();
				header.saoChroma = reader.Read<bool>();
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

				if (header.numRefIdxL0Minus1 > 14 || header.numRefIdxL1Minus1 > 14) {
					return std::nullopt;
				}

				const uint32_t numPicTotalCurr = header.NumPicTotalCurr();

				if (numPicTotalCurr == 0) {
					return std::nullopt;
				}

				if (pps->listsModificationPresent && numPicTotalCurr > 1) {
					const int bits = CeilLog2(numPicTotalCurr);

					header.listModificationL0 = reader.Read<bool>();

					if (header.listModificationL0) {
						for (uint32_t i = 0; i <= header.numRefIdxL0Minus1; i++) {
							header.listEntryL0[i] = static_cast<uint32_t>(reader.ReadBits(bits));
						}
					}

					if (header.IsB()) {
						header.listModificationL1 = reader.Read<bool>();

						if (header.listModificationL1) {
							for (uint32_t i = 0; i <= header.numRefIdxL1Minus1; i++) {
								header.listEntryL1[i] = static_cast<uint32_t>(reader.ReadBits(bits));
							}
						}
					}
				}

				if (header.IsB()) {
					header.mvdL1Zero = reader.Read<bool>();
				}
				if (pps->cabacInitPresent) {
					header.cabacInit = reader.Read<bool>();
				}

				if (header.temporalMvp) {
					if (header.IsB()) {
						header.collocatedFromL0 = reader.Read<bool>();
					}

					if ((header.collocatedFromL0 && header.numRefIdxL0Minus1 > 0)
						|| (!header.collocatedFromL0 && header.numRefIdxL1Minus1 > 0))
					{
						header.collocatedRefIdx = reader.ReadExponentialGolomb();
					}
				}

				if ((pps->weightedPred && header.sliceType == kSliceP) || (pps->weightedBipred && header.IsB())) {
					header.lumaLog2WeightDenom = reader.ReadExponentialGolomb();
					header.deltaChromaLog2WeightDenom = reader.ReadSignedExponentialGolomb();

					const int32_t chromaDenom = static_cast<int32_t>(header.lumaLog2WeightDenom)
						+ header.deltaChromaLog2WeightDenom;

					if (header.lumaLog2WeightDenom > 7 || chromaDenom < 0 || chromaDenom > 7) {
						return std::nullopt;
					}

					ParseWeights(reader, header, 0, header.numRefIdxL0Minus1 + 1, true);

					if (header.IsB()) {
						ParseWeights(reader, header, 1, header.numRefIdxL1Minus1 + 1, true);
					}
				}

				header.fiveMinusMaxNumMergeCand = reader.ReadExponentialGolomb();
			}

			header.qpDelta = reader.ReadSignedExponentialGolomb();

			if (pps->sliceChromaQpOffsetsPresent) {
				header.cbQpOffset = reader.ReadSignedExponentialGolomb();
				header.crQpOffset = reader.ReadSignedExponentialGolomb();
			}

			bool deblockingOverride = false;

			if (pps->deblockingFilterOverrideEnabled) {
				deblockingOverride = reader.Read<bool>();
			}

			if (deblockingOverride) {
				header.deblockingFilterDisabled = reader.Read<bool>();

				if (!header.deblockingFilterDisabled) {
					header.betaOffsetDiv2 = reader.ReadSignedExponentialGolomb();
					header.tcOffsetDiv2 = reader.ReadSignedExponentialGolomb();
				}
			}
			else {
				header.deblockingFilterDisabled = pps->deblockingFilterDisabled;
				header.betaOffsetDiv2 = pps->betaOffsetDiv2;
				header.tcOffsetDiv2 = pps->tcOffsetDiv2;
			}

			header.loopFilterAcrossSlices = pps->loopFilterAcrossSlices;

			if (pps->loopFilterAcrossSlices
				&& (header.saoLuma || header.saoChroma || !header.deblockingFilterDisabled))
			{
				header.loopFilterAcrossSlices = reader.Read<bool>();
			}
		}

		if (pps->tiles || pps->entropyCodingSync) {
			header.numEntryPoints = reader.ReadExponentialGolomb();

			if (header.numEntryPoints > sps->WidthInCtbs() * sps->HeightInCtbs()) {
				return std::nullopt;
			}

			if (header.numEntryPoints > 0) {
				const uint32_t offsetLenMinus1 = reader.ReadExponentialGolomb();

				if (offsetLenMinus1 > 31) {
					return std::nullopt;
				}

				for (uint32_t i = 0; i < header.numEntryPoints; i++) {
					reader.ConsumeBits(static_cast<int>(offsetLenMinus1) + 1);
				}
			}
		}

		if (pps->sliceHeaderExtensionPresent) {
			const uint32_t length = reader.ReadExponentialGolomb();

			if (length > 256) {
				return std::nullopt;
			}

			reader.ConsumeBits(static_cast<int>(length) * 8);
		}

		// byte_alignment(): a one bit and zero bits up to the next byte.
		if (reader.ReadBit() != 1) {
			return std::nullopt;
		}

		const int consumed = ConsumedBits(reader, rbsp.bytes.size());
		reader.ConsumeBits((8 - consumed % 8) % 8);

		if (!reader.Ok()) {
			return std::nullopt;
		}

		header.dataByteOffset = static_cast<uint32_t>(
			NalOffset(rbsp, static_cast<size_t>(ConsumedBits(reader, rbsp.bytes.size()) / 8)));

		if (!header.dependent) {
			lastIndependent = header;
		}

		return header;
	}

	const H265Sps * H265Parser::Sps(uint32_t id) const
	{
		auto sps = spss.find(id);

		return sps != spss.end() ? &sps->second : nullptr;
	}

	const H265Pps * H265Parser::Pps(uint32_t id) const
	{
		auto pps = ppss.find(id);

		return pps != ppss.end() ? &pps->second : nullptr;
	}

	void H265Parser::Reset()
	{
		spss.clear();
		ppss.clear();
		lastIndependent.reset();
	}
}
