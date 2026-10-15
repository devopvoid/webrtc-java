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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_H265_PARSER_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_H265_PARSER_H_

#include <array>
#include <cstdint>
#include <map>
#include <optional>
#include <span>
#include <vector>

namespace jni
{
	// A short-term reference picture set (7.3.7), as the POC differences of
	// its pictures to the current one.
	struct H265ShortTermRps
	{
		static constexpr int kMaxPictures = 16;

		int numNegative = 0;
		int numPositive = 0;
		std::array<int32_t, kMaxPictures> deltaPocS0 = {};
		std::array<bool, kMaxPictures> usedS0 = {};
		std::array<int32_t, kMaxPictures> deltaPocS1 = {};
		std::array<bool, kMaxPictures> usedS1 = {};
	};

	// What the decoder needs of a sequence parameter set (7.3.2.2), up to the
	// VUI, which decoding does not depend on.
	struct H265Sps
	{
		uint32_t generalProfileIdc = 0;
		uint32_t generalProfileCompatibility = 0;
		uint32_t chromaFormatIdc = 0;
		bool separateColourPlane = false;
		uint32_t width = 0;
		uint32_t height = 0;
		// The conformance window, in luma samples.
		uint32_t cropLeft = 0;
		uint32_t cropRight = 0;
		uint32_t cropTop = 0;
		uint32_t cropBottom = 0;
		uint32_t bitDepthLumaMinus8 = 0;
		uint32_t bitDepthChromaMinus8 = 0;
		uint32_t log2MaxPocLsbMinus4 = 0;
		// Of the highest sub-layer.
		uint32_t maxDecPicBufferingMinus1 = 0;
		uint32_t maxNumReorderPics = 0;
		uint32_t log2MinCbMinus3 = 0;
		uint32_t log2DiffMaxMinCb = 0;
		uint32_t log2MinTbMinus2 = 0;
		uint32_t log2DiffMaxMinTb = 0;
		uint32_t maxTransformDepthInter = 0;
		uint32_t maxTransformDepthIntra = 0;
		bool scalingListEnabled = false;
		bool amp = false;
		bool sao = false;
		bool pcm = false;
		uint32_t pcmBitDepthLumaMinus1 = 0;
		uint32_t pcmBitDepthChromaMinus1 = 0;
		uint32_t log2MinPcmCbMinus3 = 0;
		uint32_t log2DiffMaxMinPcmCb = 0;
		bool pcmLoopFilterDisabled = false;
		std::vector<H265ShortTermRps> shortTermRps;
		bool longTermRefsPresent = false;
		std::vector<uint32_t> ltRefPicPocLsb;
		std::vector<bool> ltUsedByCurrPic;
		bool temporalMvp = false;
		bool strongIntraSmoothing = false;

		uint32_t Log2CtbSize() const;
		uint32_t WidthInCtbs() const;
		uint32_t HeightInCtbs() const;
	};

	// A picture parameter set (7.3.2.3).
	struct H265Pps
	{
		uint32_t spsId = 0;
		bool dependentSliceSegments = false;
		bool outputFlagPresent = false;
		uint32_t numExtraSliceHeaderBits = 0;
		bool signDataHiding = false;
		bool cabacInitPresent = false;
		uint32_t numRefIdxL0DefaultMinus1 = 0;
		uint32_t numRefIdxL1DefaultMinus1 = 0;
		int32_t initQpMinus26 = 0;
		bool constrainedIntraPred = false;
		bool transformSkip = false;
		bool cuQpDelta = false;
		uint32_t diffCuQpDeltaDepth = 0;
		int32_t cbQpOffset = 0;
		int32_t crQpOffset = 0;
		bool sliceChromaQpOffsetsPresent = false;
		bool weightedPred = false;
		bool weightedBipred = false;
		bool transquantBypass = false;
		bool tiles = false;
		bool entropyCodingSync = false;
		uint32_t numTileColumnsMinus1 = 0;
		uint32_t numTileRowsMinus1 = 0;
		bool uniformSpacing = true;
		// Of all columns and rows but the last, where the spacing is not
		// uniform.
		std::vector<uint32_t> columnWidthMinus1;
		std::vector<uint32_t> rowHeightMinus1;
		bool loopFilterAcrossTiles = false;
		bool loopFilterAcrossSlices = false;
		bool deblockingFilterOverrideEnabled = false;
		bool deblockingFilterDisabled = false;
		int32_t betaOffsetDiv2 = 0;
		int32_t tcOffsetDiv2 = 0;
		bool scalingListDataPresent = false;
		bool listsModificationPresent = false;
		uint32_t log2ParallelMergeLevelMinus2 = 0;
		bool sliceHeaderExtensionPresent = false;
		bool extensionPresent = false;
	};

	// A slice segment header (7.3.6.1). A dependent slice segment carries the
	// fields of the independent one before it, which the parser fills in.
	struct H265SliceHeader
	{
		static constexpr int kMaxRefs = 16;

		uint8_t nalType = 0;
		uint8_t temporalId = 0;
		bool firstSliceInPicture = false;
		bool noOutputOfPriorPics = false;
		uint32_t ppsId = 0;
		bool dependent = false;
		uint32_t segmentAddress = 0;
		uint32_t sliceType = 0;
		bool picOutput = true;
		uint32_t pocLsb = 0;
		// The short-term set of the picture, and how many bits it took in the
		// header, 0 when it is one of the SPS.
		H265ShortTermRps shortTermRps;
		uint32_t shortTermRpsBits = 0;
		// The long-term pictures: the POC (LSBs, or all of it where the MSBs
		// are given), whether the MSBs are given and whether the current
		// picture uses them.
		std::vector<int32_t> longTermPoc;
		std::vector<bool> longTermMsbPresent;
		std::vector<bool> longTermUsed;
		bool temporalMvp = false;
		bool saoLuma = false;
		bool saoChroma = false;
		uint32_t numRefIdxL0Minus1 = 0;
		uint32_t numRefIdxL1Minus1 = 0;
		bool listModificationL0 = false;
		bool listModificationL1 = false;
		std::array<uint32_t, kMaxRefs> listEntryL0 = {};
		std::array<uint32_t, kMaxRefs> listEntryL1 = {};
		bool mvdL1Zero = false;
		bool cabacInit = false;
		bool collocatedFromL0 = true;
		uint32_t collocatedRefIdx = 0;
		uint32_t lumaLog2WeightDenom = 0;
		int32_t deltaChromaLog2WeightDenom = 0;
		std::array<std::array<int32_t, kMaxRefs>, 2> deltaLumaWeight = {};
		std::array<std::array<int32_t, kMaxRefs>, 2> lumaOffset = {};
		std::array<std::array<std::array<int32_t, 2>, kMaxRefs>, 2> deltaChromaWeight = {};
		std::array<std::array<std::array<int32_t, 2>, kMaxRefs>, 2> chromaOffset = {};
		uint32_t fiveMinusMaxNumMergeCand = 0;
		int32_t qpDelta = 0;
		int32_t cbQpOffset = 0;
		int32_t crQpOffset = 0;
		bool deblockingFilterDisabled = false;
		int32_t betaOffsetDiv2 = 0;
		int32_t tcOffsetDiv2 = 0;
		bool loopFilterAcrossSlices = false;
		uint32_t numEntryPoints = 0;
		// Where the slice data begins, in bytes from the NAL unit header, with
		// emulation prevention bytes counted.
		uint32_t dataByteOffset = 0;

		bool IsIntra() const;
		bool IsB() const;
		// NumPicTotalCurr (7-55).
		uint32_t NumPicTotalCurr() const;
	};

	// Parses the parameter sets and slice segment headers of an H.265 stream,
	// as a VA-API decoder, which leaves that to the application, needs them.
	// It takes 8 bit 4:2:0 Main and Main Still Picture streams; anything else,
	// including scaling lists and extensions, is not parsed.
	class H265Parser
	{
		public:
			// NAL unit types (table 7-1).
			static constexpr uint8_t kNalRaslN = 8;
			static constexpr uint8_t kNalRaslR = 9;
			static constexpr uint8_t kNalBlaWLp = 16;
			static constexpr uint8_t kNalIdrWRadl = 19;
			static constexpr uint8_t kNalIdrNLp = 20;
			static constexpr uint8_t kNalCraNut = 21;
			static constexpr uint8_t kNalReservedIrap23 = 23;
			static constexpr uint8_t kNalVps = 32;
			static constexpr uint8_t kNalSps = 33;
			static constexpr uint8_t kNalPps = 34;
			static constexpr uint8_t kNalEos = 36;

			// Slice types (7.4.7.1).
			static constexpr uint32_t kSliceB = 0;
			static constexpr uint32_t kSliceP = 1;
			static constexpr uint32_t kSliceI = 2;

			// Each takes a NAL unit from its header on, with emulation
			// prevention bytes, and returns false if it is broken or not one
			// the parser takes.
			bool ParseSps(std::span<const uint8_t> nal);
			bool ParsePps(std::span<const uint8_t> nal);
			std::optional<H265SliceHeader> ParseSliceHeader(std::span<const uint8_t> nal);

			const H265Sps * Sps(uint32_t id) const;
			const H265Pps * Pps(uint32_t id) const;

			void Reset();

		private:
			std::map<uint32_t, H265Sps> spss;
			std::map<uint32_t, H265Pps> ppss;
			// The last independent slice segment, whose fields a dependent one
			// takes.
			std::optional<H265SliceHeader> lastIndependent;
	};
}

#endif
