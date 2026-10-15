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

#include "media/video/codec/linux/Av1Parser.h"

#include "rtc_base/bitstream_reader.h"

#include <algorithm>
#include <cstdlib>

namespace jni
{
	namespace
	{
		constexpr int kRefsPerFrame = 7;
		constexpr int kNumRefFrames = 8;
		constexpr uint8_t kPrimaryRefNone = 7;
		constexpr uint8_t kAllFrames = 0xff;

		// The reference frames of section 6.10.24.
		constexpr int kLastFrame = 1;
		constexpr int kLast2Frame = 2;
		constexpr int kLast3Frame = 3;
		constexpr int kGoldenFrame = 4;
		constexpr int kBwdrefFrame = 5;
		constexpr int kAltref2Frame = 6;
		constexpr int kAltrefFrame = 7;

		constexpr uint8_t kSelectScreenContentTools = 2;
		constexpr uint8_t kSelectIntegerMv = 2;

		constexpr uint8_t kSuperresNum = 8;
		constexpr uint8_t kSuperresDenomMin = 9;
		constexpr int kSuperresDenomBits = 3;

		constexpr int kMaxTileWidth = 4096;
		constexpr int kMaxTileArea = 4096 * 2304;
		constexpr int kMaxTileRows = 64;
		constexpr int kMaxTileCols = 64;

		constexpr int kMaxSegments = 8;
		constexpr int kSegLvlMax = 8;
		constexpr int kSegLvlAltQ = 0;
		constexpr int kSegmentationFeatureBits[kSegLvlMax] = { 8, 6, 6, 6, 6, 3, 0, 0 };
		constexpr bool kSegmentationFeatureSigned[kSegLvlMax] = { true, true, true, true, true, false, false, false };
		constexpr int kSegmentationFeatureMax[kSegLvlMax] = { 255, 63, 63, 63, 63, 7, 0, 0 };

		// Remap_Lr_Type: none, switchable, Wiener, self-guided as coded, to
		// FrameRestorationType.
		constexpr uint8_t kRemapLrType[4] = { 0, 3, 1, 2 };

		// The global motion types and parameters of section 5.9.24.
		constexpr uint8_t kIdentity = 0;
		constexpr uint8_t kTranslation = 1;
		constexpr uint8_t kRotzoom = 2;
		constexpr uint8_t kAffine = 3;
		constexpr int kWarpedModelPrecBits = 16;
		constexpr int kGmAbsTransOnlyBits = 9;
		constexpr int kGmTransOnlyPrecBits = 3;
		constexpr int kGmAbsTransBits = 12;
		constexpr int kGmTransPrecBits = 6;
		constexpr int kGmAbsAlphaBits = 12;
		constexpr int kGmAlphaPrecBits = 15;
		constexpr int kWarpParamReduceBits = 6;
		constexpr int kDivLutBits = 8;
		constexpr int kDivLutPrecBits = 14;

		constexpr int kTotalRefsPerFrame = 8;

		// f(n) of the specification for widths that fit.
		uint32_t ReadBits(webrtc::BitstreamReader & reader, int bits)
		{
			return bits == 0 ? 0 : static_cast<uint32_t>(reader.ReadBits(bits));
		}

		// su(n): an n bit two's complement number.
		int32_t ReadSu(webrtc::BitstreamReader & reader, int bits)
		{
			int32_t value = static_cast<int32_t>(ReadBits(reader, bits));
			const int32_t signMask = 1 << (bits - 1);

			if (value & signMask) {
				value -= 2 * signMask;
			}

			return value;
		}

		// read_delta_q.
		int8_t ReadDeltaQ(webrtc::BitstreamReader & reader)
		{
			return reader.Read<bool>() ? static_cast<int8_t>(ReadSu(reader, 7)) : 0;
		}

		// uvlc().
		uint32_t ReadUvlc(webrtc::BitstreamReader & reader)
		{
			int leadingZeros = 0;

			while (reader.Ok() && !reader.Read<bool>()) {
				leadingZeros++;

				if (leadingZeros >= 32) {
					reader.Invalidate();
					return 0;
				}
			}

			return static_cast<uint32_t>((1ull << leadingZeros) - 1 + ReadBits(reader, leadingZeros));
		}

		// tile_log2.
		int TileLog2(int blockSize, int target)
		{
			int k = 0;

			while ((blockSize << k) < target) {
				k++;
			}

			return k;
		}

		int FloorLog2(uint32_t x)
		{
			int s = 0;

			while (x > 1) {
				x >>= 1;
				s++;
			}

			return s;
		}

		int64_t Round2Signed(int64_t x, int n)
		{
			if (n == 0) {
				return x;
			}

			return x >= 0 ? (x + (int64_t(1) << (n - 1))) >> n : -((-x + (int64_t(1) << (n - 1))) >> n);
		}

		int32_t InverseRecenter(int32_t r, int32_t v)
		{
			if (v > 2 * r) {
				return v;
			}
			if (v & 1) {
				return r - ((v + 1) >> 1);
			}

			return r + (v >> 1);
		}

		// decode_subexp.
		int32_t DecodeSubexp(webrtc::BitstreamReader & reader, int32_t numSyms)
		{
			int i = 0;
			int32_t mk = 0;
			const int k = 3;

			while (reader.Ok()) {
				const int b2 = i ? k + i - 1 : k;
				const int32_t a = 1 << b2;

				if (numSyms <= mk + 3 * a) {
					return static_cast<int32_t>(reader.ReadNonSymmetric(static_cast<uint32_t>(numSyms - mk))) + mk;
				}
				if (!reader.Read<bool>()) {
					return static_cast<int32_t>(ReadBits(reader, b2)) + mk;
				}

				i++;
				mk += a;
			}

			return 0;
		}

		// decode_signed_subexp_with_ref.
		int32_t DecodeSignedSubexpWithRef(webrtc::BitstreamReader & reader, int32_t low, int32_t high, int32_t r)
		{
			const int32_t mx = high - low;
			r -= low;

			const int32_t v = DecodeSubexp(reader, mx);
			const int32_t x = (r << 1) <= mx ? InverseRecenter(r, v) : mx - 1 - InverseRecenter(mx - 1 - r, v);

			return x + low;
		}

		// The divisor lookup of section 7.11.3.7, Div_Lut[i] being
		// 2^14 * 256 / (256 + i) rounded.
		int32_t DivLut(int i)
		{
			const int32_t d = 256 + i;

			return ((1 << (kDivLutPrecBits + kDivLutBits)) + d / 2) / d;
		}

		// Whether the shear of a warp is valid, section 7.11.3.6.
		bool ShearValid(const std::array<int32_t, 6> & params)
		{
			// resolve_divisor.
			const int32_t d = params[2];

			if (d == 0) {
				return false;
			}

			const uint32_t absD = static_cast<uint32_t>(std::abs(d));
			const int n = FloorLog2(absD);
			const int64_t e = absD - (int64_t(1) << n);
			const int64_t f = n > kDivLutBits ? Round2Signed(e, n - kDivLutBits) : e << (kDivLutBits - n);
			const int divShift = n + kDivLutPrecBits;
			const int64_t divFactor = d < 0 ? -DivLut(static_cast<int>(f)) : DivLut(static_cast<int>(f));

			auto clip = [](int64_t value) {
				return std::clamp<int64_t>(value, -32768, 32767);
			};

			const int64_t alpha0 = clip(params[2] - (int64_t(1) << kWarpedModelPrecBits));
			const int64_t beta0 = clip(params[3]);
			const int64_t v = int64_t(params[4]) << kWarpedModelPrecBits;
			const int64_t gamma0 = clip(Round2Signed(v * divFactor, divShift));
			const int64_t w = int64_t(params[3]) * params[4];
			const int64_t delta0 = clip(params[5] - Round2Signed(w * divFactor, divShift)
				- (int64_t(1) << kWarpedModelPrecBits));

			auto reduce = [](int64_t value) {
				return Round2Signed(value, kWarpParamReduceBits) * (int64_t(1) << kWarpParamReduceBits);
			};

			const int64_t alpha = reduce(alpha0);
			const int64_t beta = reduce(beta0);
			const int64_t gamma = reduce(gamma0);
			const int64_t delta = reduce(delta0);

			if (4 * std::llabs(alpha) + 7 * std::llabs(beta) >= (int64_t(1) << kWarpedModelPrecBits)) {
				return false;
			}
			if (4 * std::llabs(gamma) + 4 * std::llabs(delta) >= (int64_t(1) << kWarpedModelPrecBits)) {
				return false;
			}

			return true;
		}

		std::array<std::array<int32_t, 6>, 8> DefaultGmParams()
		{
			std::array<std::array<int32_t, 6>, 8> params = {};

			for (auto & ref : params) {
				for (int i = 0; i < 6; i++) {
					ref[i] = (i % 3 == 2) ? 1 << kWarpedModelPrecBits : 0;
				}
			}

			return params;
		}

		constexpr std::array<int8_t, 8> kDefaultLoopFilterRefDeltas = { 1, 0, 0, 0, -1, 0, -1, -1 };

		// Reads a leb128 value from the bytes, section 4.10.5.
		bool ReadLeb128(std::span<const uint8_t> data, size_t & position, uint64_t & value)
		{
			value = 0;

			for (int i = 0; i < 8; i++) {
				if (position >= data.size()) {
					return false;
				}

				const uint8_t byte = data[position++];
				value |= uint64_t(byte & 0x7f) << (i * 7);

				if (!(byte & 0x80)) {
					return true;
				}
			}

			return false;
		}
	}

	Av1Parser::Av1Parser()
	{
		Reset();
	}

	void Av1Parser::Reset()
	{
		sequence.reset();
		references.fill(Reference());
	}

	const std::optional<Av1SequenceHeader> & Av1Parser::SequenceHeader() const
	{
		return sequence;
	}

	std::optional<std::vector<Av1Obu>> Av1Parser::SplitObus(std::span<const uint8_t> data) const
	{
		std::vector<Av1Obu> obus;
		size_t position = 0;

		while (position < data.size()) {
			const uint8_t header = data[position++];

			if (header & 0x80) {
				// The forbidden bit.
				return std::nullopt;
			}

			Av1Obu obu;
			obu.type = static_cast<Av1ObuType>((header >> 3) & 0x0f);
			obu.hasExtension = (header & 0x04) != 0;

			const bool hasSize = (header & 0x02) != 0;

			if (obu.hasExtension) {
				if (position >= data.size()) {
					return std::nullopt;
				}

				const uint8_t extension = data[position++];
				obu.temporalId = extension >> 5;
				obu.spatialId = (extension >> 3) & 0x03;
			}

			uint64_t size = data.size() - position;

			if (hasSize && !ReadLeb128(data, position, size)) {
				return std::nullopt;
			}
			if (size > data.size() - position) {
				return std::nullopt;
			}

			obu.payload = data.subspan(position, static_cast<size_t>(size));
			position += static_cast<size_t>(size);

			// Section 7.5: the OBUs of layers outside the operating point are
			// dropped.
			const uint16_t idc = sequence ? sequence->operatingPointIdc[0] : 0;

			if (obu.type != Av1ObuType::SequenceHeader && obu.type != Av1ObuType::TemporalDelimiter
				&& obu.hasExtension && idc != 0)
			{
				const bool inTemporalLayer = (idc >> obu.temporalId) & 1;
				const bool inSpatialLayer = (idc >> (obu.spatialId + 8)) & 1;

				if (!inTemporalLayer || !inSpatialLayer) {
					continue;
				}
			}

			obus.push_back(obu);
		}

		return obus;
	}

	bool Av1Parser::ParseSequenceHeader(std::span<const uint8_t> payload)
	{
		webrtc::BitstreamReader reader(payload);
		Av1SequenceHeader header;

		header.profile = static_cast<uint8_t>(ReadBits(reader, 3));
		header.stillPicture = reader.Read<bool>();
		header.reducedStillPictureHeader = reader.Read<bool>();

		if (header.reducedStillPictureHeader) {
			header.operatingPointCount = 1;
			header.operatingPointIdc[0] = 0;
			// seq_level_idx[0].
			reader.ConsumeBits(5);
		}
		else {
			const bool timingInfoPresent = reader.Read<bool>();
			uint8_t bufferDelayLength = 0;

			if (timingInfoPresent) {
				// num_units_in_display_tick, time_scale.
				reader.ConsumeBits(64);
				header.equalPictureInterval = reader.Read<bool>();

				if (header.equalPictureInterval) {
					ReadUvlc(reader);
				}

				header.decoderModelInfoPresent = reader.Read<bool>();

				if (header.decoderModelInfoPresent) {
					bufferDelayLength = static_cast<uint8_t>(ReadBits(reader, 5) + 1);
					// num_units_in_decoding_tick.
					reader.ConsumeBits(32);
					header.bufferRemovalTimeLength = static_cast<uint8_t>(ReadBits(reader, 5) + 1);
					header.framePresentationTimeLength = static_cast<uint8_t>(ReadBits(reader, 5) + 1);
				}
			}

			const bool initialDisplayDelayPresent = reader.Read<bool>();
			header.operatingPointCount = static_cast<uint8_t>(ReadBits(reader, 5) + 1);

			for (int i = 0; i < header.operatingPointCount; i++) {
				header.operatingPointIdc[i] = static_cast<uint16_t>(ReadBits(reader, 12));

				const uint32_t seqLevelIdx = ReadBits(reader, 5);

				if (seqLevelIdx > 7) {
					// seq_tier.
					reader.ConsumeBits(1);
				}

				if (header.decoderModelInfoPresent) {
					header.decoderModelPresentForOperatingPoint[i] = reader.Read<bool>();

					if (header.decoderModelPresentForOperatingPoint[i]) {
						// decoder_buffer_delay, encoder_buffer_delay,
						// low_delay_mode_flag.
						reader.ConsumeBits(2 * bufferDelayLength + 1);
					}
				}
				if (initialDisplayDelayPresent && reader.Read<bool>()) {
					// initial_display_delay_minus_1.
					reader.ConsumeBits(4);
				}
			}
		}

		header.frameWidthBits = static_cast<uint8_t>(ReadBits(reader, 4) + 1);
		header.frameHeightBits = static_cast<uint8_t>(ReadBits(reader, 4) + 1);
		header.maxFrameWidth = ReadBits(reader, header.frameWidthBits) + 1;
		header.maxFrameHeight = ReadBits(reader, header.frameHeightBits) + 1;

		header.frameIdNumbersPresent = header.reducedStillPictureHeader ? false : reader.Read<bool>();

		if (header.frameIdNumbersPresent) {
			header.deltaFrameIdLength = static_cast<uint8_t>(ReadBits(reader, 4) + 2);
			header.frameIdLength = static_cast<uint8_t>(ReadBits(reader, 3) + 1 + header.deltaFrameIdLength);
		}

		header.use128x128Superblock = reader.Read<bool>();
		header.enableFilterIntra = reader.Read<bool>();
		header.enableIntraEdgeFilter = reader.Read<bool>();

		if (!header.reducedStillPictureHeader) {
			header.enableInterintraCompound = reader.Read<bool>();
			header.enableMaskedCompound = reader.Read<bool>();
			header.enableWarpedMotion = reader.Read<bool>();
			header.enableDualFilter = reader.Read<bool>();
			header.enableOrderHint = reader.Read<bool>();

			if (header.enableOrderHint) {
				header.enableJntComp = reader.Read<bool>();
				header.enableRefFrameMvs = reader.Read<bool>();
			}

			const bool seqChooseScreenContentTools = reader.Read<bool>();
			header.seqForceScreenContentTools = seqChooseScreenContentTools
				? kSelectScreenContentTools
				: static_cast<uint8_t>(reader.ReadBit());

			if (header.seqForceScreenContentTools > 0) {
				const bool seqChooseIntegerMv = reader.Read<bool>();
				header.seqForceIntegerMv = seqChooseIntegerMv
					? kSelectIntegerMv
					: static_cast<uint8_t>(reader.ReadBit());
			}
			else {
				header.seqForceIntegerMv = kSelectIntegerMv;
			}

			if (header.enableOrderHint) {
				header.orderHintBits = static_cast<uint8_t>(ReadBits(reader, 3) + 1);
			}
		}

		header.enableSuperres = reader.Read<bool>();
		header.enableCdef = reader.Read<bool>();
		header.enableRestoration = reader.Read<bool>();

		// color_config, section 5.5.2.
		const bool highBitdepth = reader.Read<bool>();

		if (header.profile == 2 && highBitdepth) {
			header.bitDepth = reader.Read<bool>() ? 12 : 10;
		}
		else {
			header.bitDepth = highBitdepth ? 10 : 8;
		}

		header.monochrome = header.profile == 1 ? false : reader.Read<bool>();

		if (reader.Read<bool>()) {
			header.colorPrimaries = static_cast<uint8_t>(ReadBits(reader, 8));
			header.transferCharacteristics = static_cast<uint8_t>(ReadBits(reader, 8));
			header.matrixCoefficients = static_cast<uint8_t>(ReadBits(reader, 8));
		}

		if (header.monochrome) {
			header.colorRange = reader.Read<bool>();
			header.subsamplingX = true;
			header.subsamplingY = true;
			header.separateUvDeltaQ = false;
		}
		else {
			// BT.709 primaries with the sRGB transfer and the identity matrix.
			if (header.colorPrimaries == 1 && header.transferCharacteristics == 13 && header.matrixCoefficients == 0) {
				header.colorRange = true;
				header.subsamplingX = false;
				header.subsamplingY = false;
			}
			else {
				header.colorRange = reader.Read<bool>();

				if (header.profile == 0) {
					header.subsamplingX = true;
					header.subsamplingY = true;
				}
				else if (header.profile == 1) {
					header.subsamplingX = false;
					header.subsamplingY = false;
				}
				else if (header.bitDepth == 12) {
					header.subsamplingX = reader.Read<bool>();
					header.subsamplingY = header.subsamplingX ? reader.Read<bool>() : false;
				}
				else {
					header.subsamplingX = true;
					header.subsamplingY = false;
				}

				if (header.subsamplingX && header.subsamplingY) {
					// chroma_sample_position.
					reader.ConsumeBits(2);
				}
			}

			header.separateUvDeltaQ = reader.Read<bool>();
		}

		header.filmGrainParamsPresent = reader.Read<bool>();

		if (!reader.Ok()) {
			return false;
		}

		sequence = header;

		return true;
	}

	int Av1Parser::RelativeDistance(int a, int b) const
	{
		if (!sequence->enableOrderHint) {
			return 0;
		}

		const int bits = sequence->orderHintBits;
		int diff = a - b;
		const int m = 1 << (bits - 1);

		diff = (diff & (m - 1)) - (diff & m);

		return diff;
	}

	bool Av1Parser::SetFrameRefs(Av1FrameHeader & header, uint8_t lastFrameIdx, uint8_t goldFrameIdx) const
	{
		int refFrameIdx[kRefsPerFrame];
		bool usedFrame[kNumRefFrames] = {};
		int shiftedOrderHints[kNumRefFrames];

		std::fill(std::begin(refFrameIdx), std::end(refFrameIdx), -1);

		refFrameIdx[kLastFrame - kLastFrame] = lastFrameIdx;
		refFrameIdx[kGoldenFrame - kLastFrame] = goldFrameIdx;
		usedFrame[lastFrameIdx] = true;
		usedFrame[goldFrameIdx] = true;

		const int curFrameHint = 1 << (sequence->orderHintBits - 1);

		for (int i = 0; i < kNumRefFrames; i++) {
			shiftedOrderHints[i] = curFrameHint + RelativeDistance(references[i].orderHint, header.orderHint);
		}

		if (shiftedOrderHints[lastFrameIdx] >= curFrameHint || shiftedOrderHints[goldFrameIdx] >= curFrameHint) {
			return false;
		}

		auto findLatestBackward = [&]() {
			int ref = -1;
			int latestOrderHint = 0;

			for (int i = 0; i < kNumRefFrames; i++) {
				const int hint = shiftedOrderHints[i];

				if (!usedFrame[i] && hint >= curFrameHint && (ref < 0 || hint >= latestOrderHint)) {
					ref = i;
					latestOrderHint = hint;
				}
			}

			return ref;
		};
		auto findEarliestBackward = [&]() {
			int ref = -1;
			int earliestOrderHint = 0;

			for (int i = 0; i < kNumRefFrames; i++) {
				const int hint = shiftedOrderHints[i];

				if (!usedFrame[i] && hint >= curFrameHint && (ref < 0 || hint < earliestOrderHint)) {
					ref = i;
					earliestOrderHint = hint;
				}
			}

			return ref;
		};
		auto findLatestForward = [&]() {
			int ref = -1;
			int latestOrderHint = 0;

			for (int i = 0; i < kNumRefFrames; i++) {
				const int hint = shiftedOrderHints[i];

				if (!usedFrame[i] && hint < curFrameHint && (ref < 0 || hint >= latestOrderHint)) {
					ref = i;
					latestOrderHint = hint;
				}
			}

			return ref;
		};

		int ref = findLatestBackward();

		if (ref >= 0) {
			refFrameIdx[kAltrefFrame - kLastFrame] = ref;
			usedFrame[ref] = true;
		}

		ref = findEarliestBackward();

		if (ref >= 0) {
			refFrameIdx[kBwdrefFrame - kLastFrame] = ref;
			usedFrame[ref] = true;
		}

		ref = findEarliestBackward();

		if (ref >= 0) {
			refFrameIdx[kAltref2Frame - kLastFrame] = ref;
			usedFrame[ref] = true;
		}

		constexpr int kRefFrameList[kRefsPerFrame - 2] = {
			kLast2Frame, kLast3Frame, kBwdrefFrame, kAltref2Frame, kAltrefFrame
		};

		for (int refFrame : kRefFrameList) {
			if (refFrameIdx[refFrame - kLastFrame] < 0) {
				ref = findLatestForward();

				if (ref >= 0) {
					refFrameIdx[refFrame - kLastFrame] = ref;
					usedFrame[ref] = true;
				}
			}
		}

		ref = -1;
		int earliestOrderHint = 0;

		for (int i = 0; i < kNumRefFrames; i++) {
			const int hint = shiftedOrderHints[i];

			if (ref < 0 || hint < earliestOrderHint) {
				ref = i;
				earliestOrderHint = hint;
			}
		}

		for (int i = 0; i < kRefsPerFrame; i++) {
			header.refFrameIdx[i] = static_cast<uint8_t>(refFrameIdx[i] < 0 ? ref : refFrameIdx[i]);
		}

		return true;
	}

	std::optional<Av1FrameHeader> Av1Parser::ParseFrameHeader(std::span<const uint8_t> payload, const Av1Obu & obu)
	{
		if (!sequence) {
			return std::nullopt;
		}

		const Av1SequenceHeader & seq = *sequence;
		webrtc::BitstreamReader reader(payload);
		Av1FrameHeader header;

		auto temporalPointInfo = [&]() {
			// frame_presentation_time.
			reader.ConsumeBits(seq.framePresentationTimeLength);
		};

		if (seq.reducedStillPictureHeader) {
			header.showExistingFrame = false;
			header.frameType = kAv1KeyFrame;
			header.frameIsIntra = true;
			header.showFrame = true;
			header.showableFrame = false;
		}
		else {
			header.showExistingFrame = reader.Read<bool>();

			if (header.showExistingFrame) {
				header.frameToShow = static_cast<uint8_t>(ReadBits(reader, 3));

				if (seq.decoderModelInfoPresent && !seq.equalPictureInterval) {
					temporalPointInfo();
				}
				if (seq.frameIdNumbersPresent) {
					// display_frame_id.
					reader.ConsumeBits(seq.frameIdLength);
				}

				const Reference & shown = references[header.frameToShow];

				if (!reader.Ok() || !shown.valid) {
					return std::nullopt;
				}

				header.frameType = shown.frameType;
				header.refreshFrameFlags = header.frameType == kAv1KeyFrame ? kAllFrames : 0;
				header.upscaledWidth = shown.upscaledWidth;
				header.frameWidth = shown.frameWidth;
				header.frameHeight = shown.frameHeight;
				header.renderWidth = shown.renderWidth;
				header.renderHeight = shown.renderHeight;
				header.showFrame = true;

				return header;
			}

			header.frameType = static_cast<uint8_t>(ReadBits(reader, 2));
			header.frameIsIntra = header.frameType == kAv1IntraOnlyFrame || header.frameType == kAv1KeyFrame;
			header.showFrame = reader.Read<bool>();

			if (header.showFrame && seq.decoderModelInfoPresent && !seq.equalPictureInterval) {
				temporalPointInfo();
			}

			header.showableFrame = header.showFrame
				? header.frameType != kAv1KeyFrame
				: reader.Read<bool>();

			if (header.frameType == kAv1SwitchFrame || (header.frameType == kAv1KeyFrame && header.showFrame)) {
				header.errorResilientMode = true;
			}
			else {
				header.errorResilientMode = reader.Read<bool>();
			}
		}

		if (header.frameType == kAv1KeyFrame && header.showFrame) {
			for (Reference & reference : references) {
				reference.valid = false;
				reference.orderHint = 0;
			}
		}

		header.disableCdfUpdate = reader.Read<bool>();

		header.allowScreenContentTools = seq.seqForceScreenContentTools == kSelectScreenContentTools
			? reader.Read<bool>()
			: seq.seqForceScreenContentTools != 0;

		if (header.allowScreenContentTools) {
			header.forceIntegerMv = seq.seqForceIntegerMv == kSelectIntegerMv
				? reader.Read<bool>()
				: seq.seqForceIntegerMv != 0;
		}
		else {
			header.forceIntegerMv = false;
		}

		if (header.frameIsIntra) {
			header.forceIntegerMv = true;
		}

		if (seq.frameIdNumbersPresent) {
			header.currentFrameId = ReadBits(reader, seq.frameIdLength);
		}

		bool frameSizeOverride;

		if (header.frameType == kAv1SwitchFrame) {
			frameSizeOverride = true;
		}
		else if (seq.reducedStillPictureHeader) {
			frameSizeOverride = false;
		}
		else {
			frameSizeOverride = reader.Read<bool>();
		}

		header.orderHint = static_cast<uint8_t>(ReadBits(reader, seq.orderHintBits));

		if (header.frameIsIntra || header.errorResilientMode) {
			header.primaryRefFrame = kPrimaryRefNone;
		}
		else {
			header.primaryRefFrame = static_cast<uint8_t>(ReadBits(reader, 3));
		}

		if (seq.decoderModelInfoPresent) {
			const bool bufferRemovalTimePresent = reader.Read<bool>();

			if (bufferRemovalTimePresent) {
				for (int op = 0; op < seq.operatingPointCount; op++) {
					if (!seq.decoderModelPresentForOperatingPoint[op]) {
						continue;
					}

					const uint16_t idc = seq.operatingPointIdc[op];
					const bool inTemporalLayer = (idc >> obu.temporalId) & 1;
					const bool inSpatialLayer = (idc >> (obu.spatialId + 8)) & 1;

					if (idc == 0 || (inTemporalLayer && inSpatialLayer)) {
						// buffer_removal_time.
						reader.ConsumeBits(seq.bufferRemovalTimeLength);
					}
				}
			}
		}

		if (header.frameType == kAv1SwitchFrame || (header.frameType == kAv1KeyFrame && header.showFrame)) {
			header.refreshFrameFlags = kAllFrames;
		}
		else {
			header.refreshFrameFlags = static_cast<uint8_t>(ReadBits(reader, 8));
		}

		if ((!header.frameIsIntra || header.refreshFrameFlags != kAllFrames)
			&& header.errorResilientMode && seq.enableOrderHint)
		{
			for (Reference & reference : references) {
				const uint8_t refOrderHint = static_cast<uint8_t>(ReadBits(reader, seq.orderHintBits));

				if (refOrderHint != reference.orderHint) {
					reference.valid = false;
					reference.orderHint = refOrderHint;
				}
			}
		}

		// superres_params and compute_image_size.
		auto superresParams = [&]() {
			header.useSuperres = seq.enableSuperres ? reader.Read<bool>() : false;
			header.superresDenom = header.useSuperres
				? static_cast<uint8_t>(ReadBits(reader, kSuperresDenomBits) + kSuperresDenomMin)
				: kSuperresNum;
			header.upscaledWidth = header.frameWidth;
			header.frameWidth = (header.upscaledWidth * kSuperresNum + header.superresDenom / 2) / header.superresDenom;
			header.miCols = 2 * ((header.frameWidth + 7) >> 3);
			header.miRows = 2 * ((header.frameHeight + 7) >> 3);
		};
		auto frameSize = [&]() {
			if (frameSizeOverride) {
				header.frameWidth = ReadBits(reader, seq.frameWidthBits) + 1;
				header.frameHeight = ReadBits(reader, seq.frameHeightBits) + 1;
			}
			else {
				header.frameWidth = seq.maxFrameWidth;
				header.frameHeight = seq.maxFrameHeight;
			}

			superresParams();
		};
		auto renderSize = [&]() {
			if (reader.Read<bool>()) {
				header.renderWidth = ReadBits(reader, 16) + 1;
				header.renderHeight = ReadBits(reader, 16) + 1;
			}
			else {
				header.renderWidth = header.upscaledWidth;
				header.renderHeight = header.frameHeight;
			}
		};

		if (header.frameIsIntra) {
			frameSize();
			renderSize();

			if (header.allowScreenContentTools && header.upscaledWidth == header.frameWidth) {
				header.allowIntrabc = reader.Read<bool>();
			}
		}
		else {
			bool frameRefsShortSignaling = false;

			if (seq.enableOrderHint) {
				frameRefsShortSignaling = reader.Read<bool>();

				if (frameRefsShortSignaling) {
					const uint8_t lastFrameIdx = static_cast<uint8_t>(ReadBits(reader, 3));
					const uint8_t goldFrameIdx = static_cast<uint8_t>(ReadBits(reader, 3));

					if (!reader.Ok() || !SetFrameRefs(header, lastFrameIdx, goldFrameIdx)) {
						return std::nullopt;
					}
				}
			}

			for (int i = 0; i < kRefsPerFrame; i++) {
				if (!frameRefsShortSignaling) {
					header.refFrameIdx[i] = static_cast<uint8_t>(ReadBits(reader, 3));
				}
				if (seq.frameIdNumbersPresent) {
					// delta_frame_id_minus_1.
					reader.ConsumeBits(seq.deltaFrameIdLength);
				}
			}

			for (int i = 0; i < kRefsPerFrame; i++) {
				if (!references[header.refFrameIdx[i]].valid) {
					return std::nullopt;
				}
			}

			if (frameSizeOverride && !header.errorResilientMode) {
				// frame_size_with_refs.
				bool foundRef = false;

				for (int i = 0; i < kRefsPerFrame; i++) {
					foundRef = reader.Read<bool>();

					if (foundRef) {
						const Reference & reference = references[header.refFrameIdx[i]];

						header.upscaledWidth = reference.upscaledWidth;
						header.frameWidth = header.upscaledWidth;
						header.frameHeight = reference.frameHeight;
						header.renderWidth = reference.renderWidth;
						header.renderHeight = reference.renderHeight;
						break;
					}
				}

				if (foundRef) {
					superresParams();
				}
				else {
					frameSize();
					renderSize();
				}
			}
			else {
				frameSize();
				renderSize();
			}

			header.allowHighPrecisionMv = header.forceIntegerMv ? false : reader.Read<bool>();

			// read_interpolation_filter.
			const bool isFilterSwitchable = reader.Read<bool>();
			header.interpolationFilter = isFilterSwitchable ? 4 : static_cast<uint8_t>(ReadBits(reader, 2));

			header.isMotionModeSwitchable = reader.Read<bool>();

			if (header.errorResilientMode || !seq.enableRefFrameMvs) {
				header.useRefFrameMvs = false;
			}
			else {
				header.useRefFrameMvs = reader.Read<bool>();
			}
		}

		if (seq.reducedStillPictureHeader || header.disableCdfUpdate) {
			header.disableFrameEndUpdateCdf = true;
		}
		else {
			header.disableFrameEndUpdateCdf = reader.Read<bool>();
		}

		// setup_past_independence or load_previous: what carries over.
		std::array<std::array<int32_t, 6>, 8> prevGmParams;

		if (header.primaryRefFrame == kPrimaryRefNone) {
			prevGmParams = DefaultGmParams();
			header.loopFilterRefDeltas = kDefaultLoopFilterRefDeltas;
			header.loopFilterModeDeltas = { 0, 0 };
		}
		else {
			const Reference & previous = references[header.refFrameIdx[header.primaryRefFrame]];

			prevGmParams = previous.gmParams;
			header.loopFilterRefDeltas = previous.loopFilterRefDeltas;
			header.loopFilterModeDeltas = previous.loopFilterModeDeltas;

			std::copy(&previous.featureEnabled[0][0], &previous.featureEnabled[0][0] + kMaxSegments * kSegLvlMax,
				&header.featureEnabled[0][0]);
			std::copy(&previous.featureData[0][0], &previous.featureData[0][0] + kMaxSegments * kSegLvlMax,
				&header.featureData[0][0]);
		}

		// tile_info, section 5.9.15.
		{
			const int sbCols = seq.use128x128Superblock ? ((header.miCols + 31) >> 5) : ((header.miCols + 15) >> 4);
			const int sbRows = seq.use128x128Superblock ? ((header.miRows + 31) >> 5) : ((header.miRows + 15) >> 4);
			const int sbShift = seq.use128x128Superblock ? 5 : 4;
			const int sbSize = sbShift + 2;
			const int maxTileWidthSb = kMaxTileWidth >> sbSize;
			int maxTileAreaSb = kMaxTileArea >> (2 * sbSize);
			const int minLog2TileCols = TileLog2(maxTileWidthSb, sbCols);
			const int maxLog2TileCols = TileLog2(1, std::min(sbCols, kMaxTileCols));
			const int maxLog2TileRows = TileLog2(1, std::min(sbRows, kMaxTileRows));
			const int minLog2Tiles = std::max(minLog2TileCols, TileLog2(maxTileAreaSb, sbRows * sbCols));

			header.uniformTileSpacing = reader.Read<bool>();

			if (header.uniformTileSpacing) {
				int tileColsLog2 = minLog2TileCols;

				while (tileColsLog2 < maxLog2TileCols && reader.Ok()) {
					if (!reader.Read<bool>()) {
						break;
					}

					tileColsLog2++;
				}

				const int tileWidthSb = (sbCols + (1 << tileColsLog2) - 1) >> tileColsLog2;
				int i = 0;

				for (int startSb = 0; startSb < sbCols; startSb += tileWidthSb) {
					header.tileWidthSbs[i++] = static_cast<uint16_t>(std::min(tileWidthSb, sbCols - startSb));
				}

				header.tileCols = static_cast<uint8_t>(i);
				header.tileColsLog2 = static_cast<uint8_t>(tileColsLog2);

				const int minLog2TileRows = std::max(minLog2Tiles - tileColsLog2, 0);
				int tileRowsLog2 = minLog2TileRows;

				while (tileRowsLog2 < maxLog2TileRows && reader.Ok()) {
					if (!reader.Read<bool>()) {
						break;
					}

					tileRowsLog2++;
				}

				const int tileHeightSb = (sbRows + (1 << tileRowsLog2) - 1) >> tileRowsLog2;
				i = 0;

				for (int startSb = 0; startSb < sbRows; startSb += tileHeightSb) {
					header.tileHeightSbs[i++] = static_cast<uint16_t>(std::min(tileHeightSb, sbRows - startSb));
				}

				header.tileRows = static_cast<uint8_t>(i);
				header.tileRowsLog2 = static_cast<uint8_t>(tileRowsLog2);
			}
			else {
				int widestTileSb = 0;
				int startSb = 0;
				int i = 0;

				for (; startSb < sbCols && reader.Ok(); i++) {
					if (i >= kMaxTileCols) {
						return std::nullopt;
					}

					const int maxWidth = std::min(sbCols - startSb, maxTileWidthSb);
					const int sizeSb = static_cast<int>(reader.ReadNonSymmetric(static_cast<uint32_t>(maxWidth))) + 1;

					header.tileWidthSbs[i] = static_cast<uint16_t>(sizeSb);
					widestTileSb = std::max(sizeSb, widestTileSb);
					startSb += sizeSb;
				}

				header.tileCols = static_cast<uint8_t>(i);
				header.tileColsLog2 = static_cast<uint8_t>(TileLog2(1, i));

				if (minLog2Tiles > 0) {
					maxTileAreaSb = (sbRows * sbCols) >> (minLog2Tiles + 1);
				}
				else {
					maxTileAreaSb = sbRows * sbCols;
				}

				const int maxTileHeightSb = std::max(maxTileAreaSb / std::max(widestTileSb, 1), 1);

				startSb = 0;
				i = 0;

				for (; startSb < sbRows && reader.Ok(); i++) {
					if (i >= kMaxTileRows) {
						return std::nullopt;
					}

					const int maxHeight = std::min(sbRows - startSb, maxTileHeightSb);
					const int sizeSb = static_cast<int>(reader.ReadNonSymmetric(static_cast<uint32_t>(maxHeight))) + 1;

					header.tileHeightSbs[i] = static_cast<uint16_t>(sizeSb);
					startSb += sizeSb;
				}

				header.tileRows = static_cast<uint8_t>(i);
				header.tileRowsLog2 = static_cast<uint8_t>(TileLog2(1, i));
			}

			if (header.tileColsLog2 > 0 || header.tileRowsLog2 > 0) {
				header.contextUpdateTileId = static_cast<uint16_t>(
					ReadBits(reader, header.tileRowsLog2 + header.tileColsLog2));
				header.tileSizeBytes = static_cast<uint8_t>(ReadBits(reader, 2) + 1);
			}
			else {
				header.contextUpdateTileId = 0;
			}
		}

		// quantization_params, section 5.9.12.
		header.baseQIndex = static_cast<uint8_t>(ReadBits(reader, 8));
		header.deltaQYDc = ReadDeltaQ(reader);

		if (!seq.monochrome) {
			const bool diffUvDelta = seq.separateUvDeltaQ ? reader.Read<bool>() : false;

			header.deltaQUDc = ReadDeltaQ(reader);
			header.deltaQUAc = ReadDeltaQ(reader);

			if (diffUvDelta) {
				header.deltaQVDc = ReadDeltaQ(reader);
				header.deltaQVAc = ReadDeltaQ(reader);
			}
			else {
				header.deltaQVDc = header.deltaQUDc;
				header.deltaQVAc = header.deltaQUAc;
			}
		}

		header.usingQmatrix = reader.Read<bool>();

		if (header.usingQmatrix) {
			header.qmY = static_cast<uint8_t>(ReadBits(reader, 4));
			header.qmU = static_cast<uint8_t>(ReadBits(reader, 4));
			header.qmV = seq.separateUvDeltaQ ? static_cast<uint8_t>(ReadBits(reader, 4)) : header.qmU;
		}

		// segmentation_params, section 5.9.14.
		header.segmentationEnabled = reader.Read<bool>();

		if (header.segmentationEnabled) {
			if (header.primaryRefFrame == kPrimaryRefNone) {
				header.segmentationUpdateMap = true;
				header.segmentationTemporalUpdate = false;
				header.segmentationUpdateData = true;
			}
			else {
				header.segmentationUpdateMap = reader.Read<bool>();
				header.segmentationTemporalUpdate = header.segmentationUpdateMap ? reader.Read<bool>() : false;
				header.segmentationUpdateData = reader.Read<bool>();
			}

			if (header.segmentationUpdateData) {
				for (int i = 0; i < kMaxSegments; i++) {
					for (int j = 0; j < kSegLvlMax; j++) {
						const bool enabled = reader.Read<bool>();
						int clippedValue = 0;

						if (enabled) {
							const int bits = kSegmentationFeatureBits[j];
							const int limit = kSegmentationFeatureMax[j];

							if (kSegmentationFeatureSigned[j]) {
								clippedValue = std::clamp(static_cast<int>(ReadSu(reader, 1 + bits)), -limit, limit);
							}
							else {
								clippedValue = std::clamp(static_cast<int>(ReadBits(reader, bits)), 0, limit);
							}
						}

						header.featureEnabled[i][j] = enabled;
						header.featureData[i][j] = static_cast<int16_t>(clippedValue);
					}
				}
			}
		}
		else {
			for (int i = 0; i < kMaxSegments; i++) {
				for (int j = 0; j < kSegLvlMax; j++) {
					header.featureEnabled[i][j] = false;
					header.featureData[i][j] = 0;
				}
			}
		}

		// delta_q_params and delta_lf_params.
		if (header.baseQIndex > 0) {
			header.deltaQPresent = reader.Read<bool>();
		}
		if (header.deltaQPresent) {
			header.deltaQRes = static_cast<uint8_t>(ReadBits(reader, 2));

			if (!header.allowIntrabc) {
				header.deltaLfPresent = reader.Read<bool>();
			}
			if (header.deltaLfPresent) {
				header.deltaLfRes = static_cast<uint8_t>(ReadBits(reader, 2));
				header.deltaLfMulti = reader.Read<bool>();
			}
		}

		header.codedLossless = true;

		for (int segment = 0; segment < kMaxSegments; segment++) {
			int qindex = header.baseQIndex;

			if (header.segmentationEnabled && header.featureEnabled[segment][kSegLvlAltQ]) {
				qindex = std::clamp(qindex + header.featureData[segment][kSegLvlAltQ], 0, 255);
			}

			const bool lossless = qindex == 0 && header.deltaQYDc == 0 && header.deltaQUAc == 0
				&& header.deltaQUDc == 0 && header.deltaQVAc == 0 && header.deltaQVDc == 0;

			if (!lossless) {
				header.codedLossless = false;
			}
		}

		header.allLossless = header.codedLossless && header.frameWidth == header.upscaledWidth;

		// loop_filter_params, section 5.9.11.
		if (header.codedLossless || header.allowIntrabc) {
			header.loopFilterLevel = {};
			header.loopFilterRefDeltas = kDefaultLoopFilterRefDeltas;
			header.loopFilterModeDeltas = { 0, 0 };
		}
		else {
			header.loopFilterLevel[0] = static_cast<uint8_t>(ReadBits(reader, 6));
			header.loopFilterLevel[1] = static_cast<uint8_t>(ReadBits(reader, 6));

			if (!seq.monochrome && (header.loopFilterLevel[0] || header.loopFilterLevel[1])) {
				header.loopFilterLevel[2] = static_cast<uint8_t>(ReadBits(reader, 6));
				header.loopFilterLevel[3] = static_cast<uint8_t>(ReadBits(reader, 6));
			}

			header.loopFilterSharpness = static_cast<uint8_t>(ReadBits(reader, 3));
			header.loopFilterDeltaEnabled = reader.Read<bool>();

			if (header.loopFilterDeltaEnabled) {
				header.loopFilterDeltaUpdate = reader.Read<bool>();

				if (header.loopFilterDeltaUpdate) {
					for (int i = 0; i < kTotalRefsPerFrame; i++) {
						if (reader.Read<bool>()) {
							header.loopFilterRefDeltas[i] = static_cast<int8_t>(ReadSu(reader, 7));
						}
					}
					for (int i = 0; i < 2; i++) {
						if (reader.Read<bool>()) {
							header.loopFilterModeDeltas[i] = static_cast<int8_t>(ReadSu(reader, 7));
						}
					}
				}
			}
		}

		// cdef_params, section 5.9.19.
		if (header.codedLossless || header.allowIntrabc || !seq.enableCdef) {
			header.cdefBits = 0;
			header.cdefDampingMinus3 = 0;
		}
		else {
			header.cdefDampingMinus3 = static_cast<uint8_t>(ReadBits(reader, 2));
			header.cdefBits = static_cast<uint8_t>(ReadBits(reader, 2));

			for (int i = 0; i < (1 << header.cdefBits); i++) {
				header.cdefYPriStrength[i] = static_cast<uint8_t>(ReadBits(reader, 4));
				header.cdefYSecStrength[i] = static_cast<uint8_t>(ReadBits(reader, 2));

				if (!seq.monochrome) {
					header.cdefUvPriStrength[i] = static_cast<uint8_t>(ReadBits(reader, 4));
					header.cdefUvSecStrength[i] = static_cast<uint8_t>(ReadBits(reader, 2));
				}
			}
		}

		// lr_params, section 5.9.20.
		if (!header.allLossless && !header.allowIntrabc && seq.enableRestoration) {
			bool usesLr = false;
			bool usesChromaLr = false;
			const int planes = seq.monochrome ? 1 : 3;

			for (int i = 0; i < planes; i++) {
				header.frameRestorationType[i] = kRemapLrType[ReadBits(reader, 2)];

				if (header.frameRestorationType[i] != 0) {
					usesLr = true;

					if (i > 0) {
						usesChromaLr = true;
					}
				}
			}

			if (usesLr) {
				if (seq.use128x128Superblock) {
					header.lrUnitShift = static_cast<uint8_t>(reader.ReadBit() + 1);
				}
				else {
					header.lrUnitShift = static_cast<uint8_t>(reader.ReadBit());

					if (header.lrUnitShift) {
						header.lrUnitShift += static_cast<uint8_t>(reader.ReadBit());
					}
				}

				if (seq.subsamplingX && seq.subsamplingY && usesChromaLr) {
					header.lrUvShift = static_cast<uint8_t>(reader.ReadBit());
				}
			}
		}

		// read_tx_mode.
		if (header.codedLossless) {
			header.txMode = 0;
		}
		else {
			header.txMode = reader.Read<bool>() ? 2 : 1;
		}

		// frame_reference_mode.
		header.referenceSelect = header.frameIsIntra ? false : reader.Read<bool>();

		// skip_mode_params, section 5.9.22.
		bool skipModeAllowed = false;

		if (!header.frameIsIntra && header.referenceSelect && seq.enableOrderHint) {
			int forwardIdx = -1;
			int backwardIdx = -1;
			int forwardHint = 0;
			int backwardHint = 0;

			for (int i = 0; i < kRefsPerFrame; i++) {
				const int refHint = references[header.refFrameIdx[i]].orderHint;

				if (RelativeDistance(refHint, header.orderHint) < 0) {
					if (forwardIdx < 0 || RelativeDistance(refHint, forwardHint) > 0) {
						forwardIdx = i;
						forwardHint = refHint;
					}
				}
				else if (RelativeDistance(refHint, header.orderHint) > 0) {
					if (backwardIdx < 0 || RelativeDistance(refHint, backwardHint) < 0) {
						backwardIdx = i;
						backwardHint = refHint;
					}
				}
			}

			if (forwardIdx < 0) {
				skipModeAllowed = false;
			}
			else if (backwardIdx >= 0) {
				skipModeAllowed = true;
			}
			else {
				int secondForwardIdx = -1;
				int secondForwardHint = 0;

				for (int i = 0; i < kRefsPerFrame; i++) {
					const int refHint = references[header.refFrameIdx[i]].orderHint;

					if (RelativeDistance(refHint, forwardHint) < 0) {
						if (secondForwardIdx < 0 || RelativeDistance(refHint, secondForwardHint) > 0) {
							secondForwardIdx = i;
							secondForwardHint = refHint;
						}
					}
				}

				skipModeAllowed = secondForwardIdx >= 0;
			}
		}

		header.skipModePresent = skipModeAllowed ? reader.Read<bool>() : false;

		if (header.frameIsIntra || header.errorResilientMode || !seq.enableWarpedMotion) {
			header.allowWarpedMotion = false;
		}
		else {
			header.allowWarpedMotion = reader.Read<bool>();
		}

		header.reducedTxSet = reader.Read<bool>();

		// global_motion_params, section 5.9.24.
		header.gmParams = DefaultGmParams();
		header.gmType.fill(kIdentity);
		header.gmValid.fill(true);

		if (!header.frameIsIntra) {
			for (int ref = kLastFrame; ref <= kAltrefFrame; ref++) {
				uint8_t type = kIdentity;

				if (reader.Read<bool>()) {
					if (reader.Read<bool>()) {
						type = kRotzoom;
					}
					else {
						type = reader.Read<bool>() ? kTranslation : kAffine;
					}
				}

				header.gmType[ref] = type;

				auto readGlobalParam = [&](int idx) {
					int absBits = kGmAbsAlphaBits;
					int precBits = kGmAlphaPrecBits;

					if (idx < 2) {
						if (type == kTranslation) {
							absBits = kGmAbsTransOnlyBits - !header.allowHighPrecisionMv;
							precBits = kGmTransOnlyPrecBits - !header.allowHighPrecisionMv;
						}
						else {
							absBits = kGmAbsTransBits;
							precBits = kGmTransPrecBits;
						}
					}

					const int precDiff = kWarpedModelPrecBits - precBits;
					const int32_t round = (idx % 3) == 2 ? (1 << kWarpedModelPrecBits) : 0;
					const int32_t sub = (idx % 3) == 2 ? (1 << precBits) : 0;
					const int32_t mx = 1 << absBits;
					const int32_t r = (prevGmParams[ref][idx] >> precDiff) - sub;

					header.gmParams[ref][idx] = (DecodeSignedSubexpWithRef(reader, -mx, mx + 1, r) * (1 << precDiff))
						+ round;
				};

				if (type >= kRotzoom) {
					readGlobalParam(2);
					readGlobalParam(3);

					if (type == kAffine) {
						readGlobalParam(4);
						readGlobalParam(5);
					}
					else {
						header.gmParams[ref][4] = -header.gmParams[ref][3];
						header.gmParams[ref][5] = header.gmParams[ref][2];
					}
				}
				if (type >= kTranslation) {
					readGlobalParam(0);
					readGlobalParam(1);
				}

				header.gmValid[ref] = type <= kTranslation || ShearValid(header.gmParams[ref]);
			}
		}

		// film_grain_params, section 5.9.30.
		if (seq.filmGrainParamsPresent && (header.showFrame || header.showableFrame)) {
			header.applyGrain = reader.Read<bool>();

			if (header.applyGrain) {
				// Not parsed further: a frame with film grain is left to
				// the software decoder.
				return reader.Ok() ? std::optional<Av1FrameHeader>(header) : std::nullopt;
			}
		}

		if (!reader.Ok()) {
			return std::nullopt;
		}

		const int bitsRead = static_cast<int>(payload.size() * 8) - reader.RemainingBitCount();
		header.headerBytes = static_cast<uint32_t>((bitsRead + 7) / 8);

		return header;
	}

	std::optional<Av1TileGroup> Av1Parser::ParseTileGroup(std::span<const uint8_t> payload,
		const Av1FrameHeader & header) const
	{
		webrtc::BitstreamReader reader(payload);
		Av1TileGroup group;

		const int numTiles = header.tileCols * header.tileRows;
		bool tileStartAndEndPresent = false;

		if (numTiles > 1) {
			tileStartAndEndPresent = reader.Read<bool>();
		}

		if (numTiles == 1 || !tileStartAndEndPresent) {
			group.start = 0;
			group.end = static_cast<uint16_t>(numTiles - 1);
		}
		else {
			const int tileBits = header.tileColsLog2 + header.tileRowsLog2;

			group.start = static_cast<uint16_t>(ReadBits(reader, tileBits));
			group.end = static_cast<uint16_t>(ReadBits(reader, tileBits));
		}

		if (!reader.Ok() || group.end < group.start || group.end >= numTiles) {
			return std::nullopt;
		}

		// byte_alignment.
		const int bitsRead = static_cast<int>(payload.size() * 8) - reader.RemainingBitCount();
		size_t position = static_cast<size_t>((bitsRead + 7) / 8);

		for (int tileNum = group.start; tileNum <= group.end; tileNum++) {
			Av1Tile tile;
			tile.row = static_cast<uint16_t>(tileNum / header.tileCols);
			tile.column = static_cast<uint16_t>(tileNum % header.tileCols);

			size_t tileSize;

			if (tileNum == group.end) {
				if (position >= payload.size()) {
					return std::nullopt;
				}

				tileSize = payload.size() - position;
			}
			else {
				if (payload.size() - position < header.tileSizeBytes) {
					return std::nullopt;
				}

				// tile_size_minus_1, le(TileSizeBytes).
				tileSize = 0;

				for (int i = 0; i < header.tileSizeBytes; i++) {
					tileSize |= size_t(payload[position + i]) << (8 * i);
				}

				tileSize += 1;
				position += header.tileSizeBytes;

				if (tileSize > payload.size() - position) {
					return std::nullopt;
				}
			}

			tile.offset = static_cast<uint32_t>(position);
			tile.size = static_cast<uint32_t>(tileSize);
			group.tiles.push_back(tile);

			position += tileSize;
		}

		return group;
	}

	void Av1Parser::UpdateReferences(const Av1FrameHeader & header)
	{
		if (header.showExistingFrame) {
			// A key frame shown again is loaded, and then refreshes all slots.
			if (header.frameType == kAv1KeyFrame) {
				const Reference shown = references[header.frameToShow];

				references.fill(shown);
			}

			return;
		}

		Reference reference;
		reference.valid = true;
		reference.frameId = header.currentFrameId;
		reference.upscaledWidth = header.upscaledWidth;
		reference.frameWidth = header.frameWidth;
		reference.frameHeight = header.frameHeight;
		reference.renderWidth = header.renderWidth;
		reference.renderHeight = header.renderHeight;
		reference.frameType = header.frameType;
		reference.orderHint = header.orderHint;
		reference.gmParams = header.gmParams;
		reference.loopFilterRefDeltas = header.loopFilterRefDeltas;
		reference.loopFilterModeDeltas = header.loopFilterModeDeltas;

		std::copy(&header.featureEnabled[0][0], &header.featureEnabled[0][0] + kMaxSegments * kSegLvlMax,
			&reference.featureEnabled[0][0]);
		std::copy(&header.featureData[0][0], &header.featureData[0][0] + kMaxSegments * kSegLvlMax,
			&reference.featureData[0][0]);

		for (int slot = 0; slot < kNumRefFrames; slot++) {
			if (header.refreshFrameFlags & (1 << slot)) {
				references[slot] = reference;
			}
		}
	}
}
