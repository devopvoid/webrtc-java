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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_H264_PARSER_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_H264_PARSER_H_

#include <array>
#include <cstdint>
#include <map>
#include <optional>
#include <span>
#include <vector>

namespace jni
{
	// The scaling lists of a picture (7.4.2.1.1.1), in the order they are
	// coded, which is zigzag order.
	struct H264ScalingLists
	{
		std::array<std::array<uint8_t, 16>, 6> list4x4 = {};
		std::array<std::array<uint8_t, 64>, 2> list8x8 = {};
	};

	// What the decoder needs of a sequence parameter set (7.3.2.1.1).
	struct H264Sps
	{
		uint32_t profileIdc = 0;
		uint32_t levelIdc = 0;
		uint32_t chromaFormatIdc = 1;
		uint32_t bitDepthLumaMinus8 = 0;
		uint32_t bitDepthChromaMinus8 = 0;
		bool scalingMatrixPresent = false;
		// The lists given or inferred, where scalingMatrixPresent is set.
		H264ScalingLists scalingLists;
		uint32_t log2MaxFrameNumMinus4 = 0;
		uint32_t pocType = 0;
		uint32_t log2MaxPocLsbMinus4 = 0;
		bool deltaPicOrderAlwaysZero = false;
		int32_t offsetForNonRefPic = 0;
		int32_t offsetForTopToBottomField = 0;
		std::vector<int32_t> offsetForRefFrame;
		uint32_t maxNumRefFrames = 0;
		bool gapsInFrameNumAllowed = false;
		uint32_t widthInMbs = 0;
		uint32_t heightInMbs = 0;
		bool frameMbsOnly = true;
		bool direct8x8Inference = false;
		// The frame cropping, in luma samples.
		uint32_t cropLeft = 0;
		uint32_t cropRight = 0;
		uint32_t cropTop = 0;
		uint32_t cropBottom = 0;
		// From the VUI, where it says.
		std::optional<uint32_t> maxNumReorderFrames;

		uint32_t MaxFrameNum() const;
	};

	// A picture parameter set (7.3.2.2).
	struct H264Pps
	{
		uint32_t spsId = 0;
		bool entropyCodingMode = false;
		bool bottomFieldPicOrderInFramePresent = false;
		uint32_t numSliceGroupsMinus1 = 0;
		uint32_t numRefIdxL0DefaultMinus1 = 0;
		uint32_t numRefIdxL1DefaultMinus1 = 0;
		bool weightedPred = false;
		uint32_t weightedBipredIdc = 0;
		int32_t picInitQpMinus26 = 0;
		int32_t picInitQsMinus26 = 0;
		int32_t chromaQpIndexOffset = 0;
		bool deblockingFilterControlPresent = false;
		bool constrainedIntraPred = false;
		bool redundantPicCntPresent = false;
		bool transform8x8Mode = false;
		int32_t secondChromaQpIndexOffset = 0;
		bool scalingMatrixPresent = false;
		// The lists as coded, and which were.
		std::array<bool, 8> scalingListPresent = {};
		std::array<bool, 8> useDefaultScalingList = {};
		H264ScalingLists scalingLists;
	};

	// A reference picture list modification (7.3.3.1), or a memory
	// management control operation (7.3.3.3).
	struct H264Operation
	{
		uint32_t operation = 0;
		uint32_t value = 0;
		// For memory management: long_term_frame_idx of operations 3 and 6.
		uint32_t longTermFrameIdx = 0;
	};

	// A slice header (7.3.3) of a frame.
	struct H264SliceHeader
	{
		static constexpr int kMaxRefs = 32;

		uint8_t nalType = 0;
		uint8_t nalRefIdc = 0;
		uint32_t firstMbInSlice = 0;
		// slice_type modulo 5.
		uint32_t sliceType = 0;
		uint32_t ppsId = 0;
		uint32_t frameNum = 0;
		uint32_t idrPicId = 0;
		uint32_t pocLsb = 0;
		int32_t deltaPocBottom = 0;
		std::array<int32_t, 2> deltaPoc = {};
		uint32_t redundantPicCnt = 0;
		bool directSpatialMvPred = false;
		uint32_t numRefIdxL0Minus1 = 0;
		uint32_t numRefIdxL1Minus1 = 0;
		std::array<std::vector<H264Operation>, 2> listModifications;
		uint32_t lumaLog2WeightDenom = 0;
		uint32_t chromaLog2WeightDenom = 0;
		// Whether any weight of a list was given, and the weights and offsets,
		// the default ones where none were given.
		std::array<bool, 2> lumaWeightsGiven = {};
		std::array<bool, 2> chromaWeightsGiven = {};
		std::array<std::array<int32_t, kMaxRefs>, 2> lumaWeight = {};
		std::array<std::array<int32_t, kMaxRefs>, 2> lumaOffset = {};
		std::array<std::array<std::array<int32_t, 2>, kMaxRefs>, 2> chromaWeight = {};
		std::array<std::array<std::array<int32_t, 2>, kMaxRefs>, 2> chromaOffset = {};
		bool noOutputOfPriorPics = false;
		bool longTermReference = false;
		bool adaptiveRefPicMarking = false;
		std::vector<H264Operation> memoryManagement;
		uint32_t cabacInitIdc = 0;
		int32_t qpDelta = 0;
		uint32_t disableDeblockingFilterIdc = 0;
		int32_t alphaOffsetDiv2 = 0;
		int32_t betaOffsetDiv2 = 0;
		// Where the slice data begins, in bits from the NAL unit header, with
		// emulation prevention bytes not counted, as VA-API takes it.
		uint32_t dataBitOffset = 0;

		bool IsIdr() const;
		bool IsIntra() const;
		bool IsB() const;
	};

	// Parses the parameter sets and slice headers of an H.264 stream, as a
	// VA-API decoder, which leaves that to the application, needs them. It
	// takes progressive 8 bit 4:2:0 streams without slice groups; anything
	// else is not parsed, so that the software decoder takes it.
	class H264Parser
	{
		public:
			// NAL unit types (table 7-1).
			static constexpr uint8_t kNalSlice = 1;
			static constexpr uint8_t kNalIdr = 5;
			static constexpr uint8_t kNalSps = 7;
			static constexpr uint8_t kNalPps = 8;
			static constexpr uint8_t kNalEndOfSequence = 10;

			// Slice types modulo 5 (table 7-6).
			static constexpr uint32_t kSliceP = 0;
			static constexpr uint32_t kSliceB = 1;
			static constexpr uint32_t kSliceI = 2;

			// Each takes a NAL unit from its header on, with emulation
			// prevention bytes, and returns false if it is broken or not one
			// the parser takes.
			bool ParseSps(std::span<const uint8_t> nal);
			bool ParsePps(std::span<const uint8_t> nal);
			std::optional<H264SliceHeader> ParseSliceHeader(std::span<const uint8_t> nal) const;

			const H264Sps * Sps(uint32_t id) const;
			const H264Pps * Pps(uint32_t id) const;

			// The scaling lists a picture is decoded with: of the PPS, falling
			// back to those of the SPS, or flat ones (7.4.2.2).
			static H264ScalingLists ScalingLists(const H264Sps & sps, const H264Pps & pps);

			void Reset();

		private:
			std::map<uint32_t, H264Sps> spss;
			std::map<uint32_t, H264Pps> ppss;
	};
}

#endif
