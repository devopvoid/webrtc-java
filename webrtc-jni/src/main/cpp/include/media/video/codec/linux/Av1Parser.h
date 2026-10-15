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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_AV1_PARSER_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_AV1_PARSER_H_

#include <array>
#include <cstdint>
#include <optional>
#include <span>
#include <vector>

namespace jni
{
	// The OBU types of section 6.2.2 of the AV1 specification.
	enum class Av1ObuType : uint8_t
	{
		SequenceHeader = 1,
		TemporalDelimiter = 2,
		FrameHeader = 3,
		TileGroup = 4,
		Metadata = 5,
		Frame = 6,
		RedundantFrameHeader = 7,
		TileList = 8,
		Padding = 15
	};

	// The frame types of section 6.8.2.
	enum Av1FrameType : uint8_t
	{
		kAv1KeyFrame = 0,
		kAv1InterFrame = 1,
		kAv1IntraOnlyFrame = 2,
		kAv1SwitchFrame = 3
	};

	// One OBU of a temporal unit, its payload a span of the data it came in.
	struct Av1Obu
	{
		Av1ObuType type = Av1ObuType::Padding;
		bool hasExtension = false;
		uint8_t temporalId = 0;
		uint8_t spatialId = 0;
		std::span<const uint8_t> payload;
	};

	// The sequence header OBU, as section 5.5 defines it, with what a decoder
	// of operating point 0 needs.
	struct Av1SequenceHeader
	{
		uint8_t profile = 0;
		bool stillPicture = false;
		bool reducedStillPictureHeader = false;

		bool decoderModelInfoPresent = false;
		bool equalPictureInterval = false;
		uint8_t bufferRemovalTimeLength = 0;
		uint8_t framePresentationTimeLength = 0;
		uint8_t operatingPointCount = 1;
		std::array<uint16_t, 32> operatingPointIdc = {};
		std::array<bool, 32> decoderModelPresentForOperatingPoint = {};

		uint8_t frameWidthBits = 0;
		uint8_t frameHeightBits = 0;
		uint32_t maxFrameWidth = 0;
		uint32_t maxFrameHeight = 0;

		bool frameIdNumbersPresent = false;
		uint8_t deltaFrameIdLength = 0;
		uint8_t frameIdLength = 0;

		bool use128x128Superblock = false;
		bool enableFilterIntra = false;
		bool enableIntraEdgeFilter = false;
		bool enableInterintraCompound = false;
		bool enableMaskedCompound = false;
		bool enableWarpedMotion = false;
		bool enableDualFilter = false;
		bool enableOrderHint = false;
		bool enableJntComp = false;
		bool enableRefFrameMvs = false;
		// 0, 1, or 2 for a choice made by each frame.
		uint8_t seqForceScreenContentTools = 2;
		uint8_t seqForceIntegerMv = 2;
		uint8_t orderHintBits = 0;

		bool enableSuperres = false;
		bool enableCdef = false;
		bool enableRestoration = false;

		uint8_t bitDepth = 8;
		bool monochrome = false;
		uint8_t colorPrimaries = 2;
		uint8_t transferCharacteristics = 2;
		uint8_t matrixCoefficients = 2;
		bool colorRange = false;
		bool subsamplingX = true;
		bool subsamplingY = true;
		bool separateUvDeltaQ = false;

		bool filmGrainParamsPresent = false;
	};

	// The frame header OBU, the uncompressed header of section 5.9, with what
	// earlier frames left that it depends on resolved: the loop filter deltas,
	// the segmentation and the global motion that carry over from the primary
	// reference frame, and the size taken from a reference frame.
	struct Av1FrameHeader
	{
		bool showExistingFrame = false;
		uint8_t frameToShow = 0;

		uint8_t frameType = kAv1KeyFrame;
		bool frameIsIntra = true;
		bool showFrame = true;
		bool showableFrame = false;
		bool errorResilientMode = false;
		bool disableCdfUpdate = false;
		bool allowScreenContentTools = false;
		bool forceIntegerMv = false;
		uint32_t currentFrameId = 0;
		uint8_t orderHint = 0;
		// 7 for none.
		uint8_t primaryRefFrame = 7;
		uint8_t refreshFrameFlags = 0;

		// FrameWidth is the coded width, UpscaledWidth the width with
		// superres; they differ only with superres.
		uint32_t frameWidth = 0;
		uint32_t frameHeight = 0;
		uint32_t upscaledWidth = 0;
		uint32_t renderWidth = 0;
		uint32_t renderHeight = 0;
		bool useSuperres = false;
		uint8_t superresDenom = 8;
		uint32_t miCols = 0;
		uint32_t miRows = 0;

		bool allowIntrabc = false;
		std::array<uint8_t, 7> refFrameIdx = {};
		bool allowHighPrecisionMv = false;
		// 0 to 3, or 4 for switchable.
		uint8_t interpolationFilter = 0;
		bool isMotionModeSwitchable = false;
		bool useRefFrameMvs = false;
		bool disableFrameEndUpdateCdf = true;

		bool uniformTileSpacing = true;
		uint8_t tileCols = 1;
		uint8_t tileRows = 1;
		uint8_t tileColsLog2 = 0;
		uint8_t tileRowsLog2 = 0;
		// The width and height of each tile column and row in superblocks.
		std::array<uint16_t, 64> tileWidthSbs = {};
		std::array<uint16_t, 64> tileHeightSbs = {};
		uint16_t contextUpdateTileId = 0;
		uint8_t tileSizeBytes = 4;

		uint8_t baseQIndex = 0;
		int8_t deltaQYDc = 0;
		int8_t deltaQUDc = 0;
		int8_t deltaQUAc = 0;
		int8_t deltaQVDc = 0;
		int8_t deltaQVAc = 0;
		bool usingQmatrix = false;
		uint8_t qmY = 0;
		uint8_t qmU = 0;
		uint8_t qmV = 0;

		bool segmentationEnabled = false;
		bool segmentationUpdateMap = false;
		bool segmentationTemporalUpdate = false;
		bool segmentationUpdateData = false;
		bool featureEnabled[8][8] = {};
		int16_t featureData[8][8] = {};

		bool deltaQPresent = false;
		uint8_t deltaQRes = 0;
		bool deltaLfPresent = false;
		uint8_t deltaLfRes = 0;
		bool deltaLfMulti = false;

		bool codedLossless = false;
		bool allLossless = false;

		// Luma vertical and horizontal, then U and V.
		std::array<uint8_t, 4> loopFilterLevel = {};
		uint8_t loopFilterSharpness = 0;
		bool loopFilterDeltaEnabled = false;
		bool loopFilterDeltaUpdate = false;
		std::array<int8_t, 8> loopFilterRefDeltas = {};
		std::array<int8_t, 2> loopFilterModeDeltas = {};

		uint8_t cdefDampingMinus3 = 0;
		uint8_t cdefBits = 0;
		// The secondary strengths as coded, 3 standing for 4.
		std::array<uint8_t, 8> cdefYPriStrength = {};
		std::array<uint8_t, 8> cdefYSecStrength = {};
		std::array<uint8_t, 8> cdefUvPriStrength = {};
		std::array<uint8_t, 8> cdefUvSecStrength = {};

		// FrameRestorationType: none, Wiener, self-guided, switchable.
		std::array<uint8_t, 3> frameRestorationType = {};
		uint8_t lrUnitShift = 0;
		uint8_t lrUvShift = 0;

		// Only 4x4, largest, or select.
		uint8_t txMode = 0;
		bool referenceSelect = false;
		bool skipModePresent = false;
		bool allowWarpedMotion = false;
		bool reducedTxSet = false;

		// By reference frame, LAST_FRAME (1) to ALTREF_FRAME (7): identity,
		// translation, rotation and zoom, or affine; and whether the shear
		// of the warp is valid.
		std::array<uint8_t, 8> gmType = {};
		std::array<std::array<int32_t, 6>, 8> gmParams = {};
		std::array<bool, 8> gmValid = {};

		// Film grain applied to the output, which is not parsed further.
		bool applyGrain = false;

		// The size of the uncompressed header in bytes, after which the tile
		// group of a frame OBU starts.
		uint32_t headerBytes = 0;
	};

	// One tile of a frame, where its data is in the tile group it came in.
	struct Av1Tile
	{
		uint16_t row = 0;
		uint16_t column = 0;
		uint32_t offset = 0;
		uint32_t size = 0;
	};

	// The tiles of one tile group OBU.
	struct Av1TileGroup
	{
		uint16_t start = 0;
		uint16_t end = 0;
		std::vector<Av1Tile> tiles;
	};

	// Parses the sequence and frame headers of an AV1 stream, in the order
	// its frames are decoded, keeping what carries over from one frame to the
	// next in the eight reference slots.
	class Av1Parser
	{
		public:
			Av1Parser();

			// Forgets the sequence header and the reference frames, as before a
			// new stream.
			void Reset();

			// The OBUs of a temporal unit, or nothing if they do not fit into
			// the data. Takes the OBUs of operating point 0 only, where the
			// sequence header is known.
			std::optional<std::vector<Av1Obu>> SplitObus(std::span<const uint8_t> data) const;

			// Parses a sequence header OBU. Returns false if it is not valid.
			bool ParseSequenceHeader(std::span<const uint8_t> payload);

			const std::optional<Av1SequenceHeader> & SequenceHeader() const;

			// Parses a frame header OBU, or the header of a frame OBU. Returns
			// nothing if it is not valid, or refers to a reference frame that
			// is not there.
			std::optional<Av1FrameHeader> ParseFrameHeader(std::span<const uint8_t> payload, const Av1Obu & obu);

			// Parses a tile group OBU of the frame. Returns nothing if it is
			// not valid.
			std::optional<Av1TileGroup> ParseTileGroup(std::span<const uint8_t> payload,
				const Av1FrameHeader & header) const;

			// Takes the frame into the reference slots its refresh flags name,
			// once it is decoded, or, for a key frame shown again, all of them;
			// section 7.20 and 7.21 of the specification.
			void UpdateReferences(const Av1FrameHeader & header);

		private:
			// What a reference slot holds of the frame in it.
			struct Reference
			{
				bool valid = false;
				uint32_t frameId = 0;
				uint32_t upscaledWidth = 0;
				uint32_t frameWidth = 0;
				uint32_t frameHeight = 0;
				uint32_t renderWidth = 0;
				uint32_t renderHeight = 0;
				uint8_t frameType = kAv1KeyFrame;
				uint8_t orderHint = 0;
				std::array<std::array<int32_t, 6>, 8> gmParams = {};
				std::array<int8_t, 8> loopFilterRefDeltas = {};
				std::array<int8_t, 2> loopFilterModeDeltas = {};
				bool featureEnabled[8][8] = {};
				int16_t featureData[8][8] = {};
			};

			int RelativeDistance(int a, int b) const;
			// What the specification calls set_frame_refs.
			bool SetFrameRefs(Av1FrameHeader & header, uint8_t lastFrameIdx, uint8_t goldFrameIdx) const;

		private:
			std::optional<Av1SequenceHeader> sequence;
			std::array<Reference, 8> references;
	};
}

#endif
