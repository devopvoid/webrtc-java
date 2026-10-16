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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_VP9_FRAME_HEADER_PARSER_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_VP9_FRAME_HEADER_PARSER_H_

#include <array>
#include <cstdint>
#include <optional>
#include <span>
#include <utility>
#include <vector>

namespace jni
{
	// What a hardware decoder needs to know about one segment of a VP9 frame:
	// its quantizer scales and loop filter levels, worked out from the frame
	// and its segmentation as libvpx does.
	struct Vp9Segment
	{
		bool referenceEnabled = false;
		uint8_t reference = 0;
		bool skipped = false;

		// By reference frame (intra, last, golden, altref) and mode delta
		// (zero motion vector or not).
		uint8_t filterLevel[4][2] = {};

		int16_t lumaDcScale = 0;
		int16_t lumaAcScale = 0;
		int16_t chromaDcScale = 0;
		int16_t chromaAcScale = 0;
	};

	// The uncompressed header of a VP9 frame, as section 6.2 of the VP9
	// bitstream specification defines it, with what earlier frames left that
	// it depends on resolved: the size taken from a reference frame, the
	// loop filter deltas and the segmentation that carry over.
	struct Vp9FrameHeader
	{
		uint8_t profile = 0;
		uint8_t bitDepth = 8;
		bool subsamplingX = true;
		bool subsamplingY = true;

		bool showExistingFrame = false;
		uint8_t frameToShow = 0;

		bool keyFrame = false;
		bool showFrame = false;
		bool errorResilient = false;
		bool intraOnly = false;
		uint8_t resetFrameContext = 0;
		uint8_t refreshFrameFlags = 0;

		// The slots of the last, golden and altref frames, and their sign
		// bias.
		std::array<uint8_t, 3> referenceSlots = {};
		std::array<bool, 3> referenceSignBias = {};

		uint32_t width = 0;
		uint32_t height = 0;

		bool allowHighPrecisionMv = false;
		// The interpolation filter as coded (smooth, regular, sharp,
		// bilinear), or 4 for switchable.
		uint8_t interpolationFilter = 0;

		bool refreshFrameContext = false;
		bool frameParallelDecoding = false;
		uint8_t frameContextIndex = 0;

		uint8_t filterLevel = 0;
		uint8_t sharpness = 0;

		uint8_t baseQIndex = 0;
		bool lossless = false;

		bool segmentationEnabled = false;
		bool segmentationUpdateMap = false;
		bool segmentationTemporalUpdate = false;
		std::array<uint8_t, 7> segmentationTreeProbs = {};
		std::array<uint8_t, 3> segmentationPredProbs = {};
		std::array<Vp9Segment, 8> segments = {};

		uint8_t log2TileColumns = 0;
		uint8_t log2TileRows = 0;

		// The sizes of the uncompressed and compressed headers, in bytes.
		uint32_t uncompressedHeaderSize = 0;
		uint32_t compressedHeaderSize = 0;
	};

	// Parses the uncompressed headers of the frames of a VP9 stream, in the
	// order they are decoded, keeping what carries over from one to the
	// next. Profile 0 only, which is all a VA-API decoder is asked for here.
	class Vp9FrameHeaderParser
	{
		public:
			Vp9FrameHeaderParser();

			// Forgets what earlier frames left, as before a new stream.
			void Reset();

			// Parses the header of the frame and takes over what it changes.
			// Returns nothing if the frame is not valid profile 0 VP9, or
			// refers to a reference frame that is not there.
			std::optional<Vp9FrameHeader> Parse(std::span<const uint8_t> frame);

			// The frames of a superframe, or the frame itself, as spans of
			// the data.
			static std::vector<std::span<const uint8_t>> SplitSuperframe(std::span<const uint8_t> data);

			// The size a frame codes itself, without the state of the stream;
			// none for an inter frame that takes the size of a reference.
			static std::optional<std::pair<uint32_t, uint32_t>> FrameSize(std::span<const uint8_t> frame);

		private:
			// What the specification calls setup_past_independence.
			void SetupPastIndependence();
			void ComputeSegments(Vp9FrameHeader & header, int8_t yDcDelta, int8_t uvDcDelta, int8_t uvAcDelta) const;

		private:
			std::array<int8_t, 4> loopFilterRefDeltas;
			std::array<int8_t, 2> loopFilterModeDeltas;
			bool loopFilterDeltaEnabled;

			std::array<uint8_t, 7> segmentationTreeProbs;
			std::array<uint8_t, 3> segmentationPredProbs;
			bool segmentationAbsoluteValues;
			bool featureEnabled[8][4];
			int16_t featureData[8][4];

			// The size of the frame in each reference slot, zero where there
			// is none.
			std::array<uint32_t, 8> slotWidths;
			std::array<uint32_t, 8> slotHeights;
	};
}

#endif
