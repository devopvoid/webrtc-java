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

#include "media/video/codec/linux/Vp9FrameHeaderParser.h"

#include "rtc_base/bitstream_reader.h"

#include <algorithm>

// The quantizer lookup of libvpx, which WebRTC links for its VP9 codecs.
// Declared here since its headers are not among WebRTC's; the bit depth is
// an enum there, 8 for 8 bit.
extern "C" {
	int16_t vp9_dc_quant(int qindex, int delta, int bitDepth);
	int16_t vp9_ac_quant(int qindex, int delta, int bitDepth);
}

namespace jni
{
	namespace
	{
		constexpr uint32_t kFrameSyncCode = 0x498342;
		constexpr uint8_t kColorSpaceRgb = 7;

		constexpr int kMaxLoopFilter = 63;
		constexpr int kMaxQIndex = 255;

		// The segmentation features: alternate quantizer, alternate loop
		// filter level, reference frame and skip.
		constexpr int kFeatureQuantizer = 0;
		constexpr int kFeatureLoopFilter = 1;
		constexpr int kFeatureReference = 2;
		constexpr int kFeatureSkip = 3;
		constexpr int kFeatureBits[4] = { 8, 6, 2, 0 };
		constexpr bool kFeatureSigned[4] = { true, true, false, false };

		// Tiles are 4 to 64 superblocks of 64x64 wide.
		constexpr uint32_t kMinTileWidthB64 = 4;
		constexpr uint32_t kMaxTileWidthB64 = 64;

		// su(n) of the specification: a magnitude of n bits and a sign.
		int ReadSigned(webrtc::BitstreamReader & reader, int bits)
		{
			const int value = static_cast<int>(reader.ReadBits(bits));

			return reader.Read<bool>() ? -value : value;
		}

		uint8_t ReadProbability(webrtc::BitstreamReader & reader)
		{
			return reader.Read<bool>() ? reader.Read<uint8_t>() : 255;
		}

		int8_t ReadDeltaQ(webrtc::BitstreamReader & reader)
		{
			return reader.Read<bool>() ? static_cast<int8_t>(ReadSigned(reader, 4)) : 0;
		}

		uint8_t ClampLevel(int level)
		{
			return static_cast<uint8_t>(std::clamp(level, 0, kMaxLoopFilter));
		}
	}

	Vp9FrameHeaderParser::Vp9FrameHeaderParser()
	{
		Reset();
	}

	void Vp9FrameHeaderParser::Reset()
	{
		SetupPastIndependence();

		segmentationTreeProbs.fill(255);
		segmentationPredProbs.fill(255);
		slotWidths.fill(0);
		slotHeights.fill(0);
	}

	void Vp9FrameHeaderParser::SetupPastIndependence()
	{
		loopFilterDeltaEnabled = true;
		loopFilterRefDeltas = { 1, 0, -1, -1 };
		loopFilterModeDeltas = { 0, 0 };

		segmentationAbsoluteValues = false;

		for (int segment = 0; segment < 8; segment++) {
			for (int feature = 0; feature < 4; feature++) {
				featureEnabled[segment][feature] = false;
				featureData[segment][feature] = 0;
			}
		}
	}

	std::optional<Vp9FrameHeader> Vp9FrameHeaderParser::Parse(std::span<const uint8_t> frame)
	{
		webrtc::BitstreamReader reader(frame);
		Vp9FrameHeader header;

		if (reader.ReadBits(2) != 2) {
			return std::nullopt;
		}

		// The profile has its low bit first.
		header.profile = static_cast<uint8_t>(reader.ReadBit());
		header.profile |= static_cast<uint8_t>(reader.ReadBit() << 1);

		if (header.profile != 0) {
			return std::nullopt;
		}

		if (reader.Read<bool>()) {
			header.showExistingFrame = true;
			header.frameToShow = static_cast<uint8_t>(reader.ReadBits(3));

			if (!reader.Ok() || slotWidths[header.frameToShow] == 0) {
				return std::nullopt;
			}

			header.width = slotWidths[header.frameToShow];
			header.height = slotHeights[header.frameToShow];

			return header;
		}

		header.keyFrame = !reader.Read<bool>();
		header.showFrame = reader.Read<bool>();
		header.errorResilient = reader.Read<bool>();

		bool readSize = true;

		if (header.keyFrame) {
			if (reader.ReadBits(24) != kFrameSyncCode) {
				return std::nullopt;
			}

			// The color configuration of profile 0: 8 bit 4:2:0, whose
			// color space must not be RGB, which needs 4:4:4.
			if (reader.ReadBits(3) == kColorSpaceRgb) {
				return std::nullopt;
			}

			reader.ConsumeBits(1);

			header.refreshFrameFlags = 0xFF;
		}
		else {
			header.intraOnly = header.showFrame ? false : reader.Read<bool>();
			header.resetFrameContext = header.errorResilient ? 0 : static_cast<uint8_t>(reader.ReadBits(2));

			if (header.intraOnly) {
				if (reader.ReadBits(24) != kFrameSyncCode) {
					return std::nullopt;
				}

				// Profile 0 carries no color configuration here: 8 bit 4:2:0.
				header.refreshFrameFlags = reader.Read<uint8_t>();
			}
			else {
				header.refreshFrameFlags = reader.Read<uint8_t>();

				for (int i = 0; i < 3; i++) {
					header.referenceSlots[i] = static_cast<uint8_t>(reader.ReadBits(3));
					header.referenceSignBias[i] = reader.Read<bool>();

					if (slotWidths[header.referenceSlots[i]] == 0) {
						return std::nullopt;
					}
				}

				// The size of a reference frame, or one of its own.
				for (int i = 0; i < 3; i++) {
					if (reader.Read<bool>()) {
						header.width = slotWidths[header.referenceSlots[i]];
						header.height = slotHeights[header.referenceSlots[i]];
						readSize = false;
						break;
					}
				}
			}
		}

		if (readSize) {
			header.width = static_cast<uint32_t>(reader.Read<uint16_t>()) + 1;
			header.height = static_cast<uint32_t>(reader.Read<uint16_t>()) + 1;
		}

		// The render size, which a decoder does not need.
		if (reader.Read<bool>()) {
			reader.ConsumeBits(32);
		}

		if (!header.keyFrame && !header.intraOnly) {
			header.allowHighPrecisionMv = reader.Read<bool>();
			header.interpolationFilter = reader.Read<bool>() ? 4 : static_cast<uint8_t>(reader.ReadBits(2));
		}

		if (!header.errorResilient) {
			header.refreshFrameContext = reader.Read<bool>();
			header.frameParallelDecoding = reader.Read<bool>();
		}
		else {
			header.refreshFrameContext = false;
			header.frameParallelDecoding = true;
		}

		header.frameContextIndex = static_cast<uint8_t>(reader.ReadBits(2));

		if (header.keyFrame || header.intraOnly || header.errorResilient) {
			SetupPastIndependence();
		}
		// As FFmpeg does, which drivers are used with: the index of a key
		// frame or an intra-only frame is not used.
		if (header.keyFrame || header.intraOnly) {
			header.frameContextIndex = 0;
		}

		// Loop filter parameters.
		header.filterLevel = static_cast<uint8_t>(reader.ReadBits(6));
		header.sharpness = static_cast<uint8_t>(reader.ReadBits(3));
		loopFilterDeltaEnabled = reader.Read<bool>();

		if (loopFilterDeltaEnabled && reader.Read<bool>()) {
			for (int8_t & delta : loopFilterRefDeltas) {
				if (reader.Read<bool>()) {
					delta = static_cast<int8_t>(ReadSigned(reader, 6));
				}
			}
			for (int8_t & delta : loopFilterModeDeltas) {
				if (reader.Read<bool>()) {
					delta = static_cast<int8_t>(ReadSigned(reader, 6));
				}
			}
		}

		// Quantization parameters.
		header.baseQIndex = reader.Read<uint8_t>();
		const int8_t yDcDelta = ReadDeltaQ(reader);
		const int8_t uvDcDelta = ReadDeltaQ(reader);
		const int8_t uvAcDelta = ReadDeltaQ(reader);
		header.lossless = header.baseQIndex == 0 && yDcDelta == 0 && uvDcDelta == 0 && uvAcDelta == 0;

		// Segmentation parameters.
		header.segmentationEnabled = reader.Read<bool>();

		if (header.segmentationEnabled) {
			header.segmentationUpdateMap = reader.Read<bool>();

			if (header.segmentationUpdateMap) {
				for (uint8_t & probability : segmentationTreeProbs) {
					probability = ReadProbability(reader);
				}

				header.segmentationTemporalUpdate = reader.Read<bool>();

				for (uint8_t & probability : segmentationPredProbs) {
					probability = header.segmentationTemporalUpdate ? ReadProbability(reader) : 255;
				}
			}

			if (reader.Read<bool>()) {
				segmentationAbsoluteValues = reader.Read<bool>();

				for (int segment = 0; segment < 8; segment++) {
					for (int feature = 0; feature < 4; feature++) {
						int value = 0;

						featureEnabled[segment][feature] = reader.Read<bool>();

						if (featureEnabled[segment][feature]) {
							value = static_cast<int>(reader.ReadBits(kFeatureBits[feature]));

							if (kFeatureSigned[feature] && reader.Read<bool>()) {
								value = -value;
							}
						}

						featureData[segment][feature] = static_cast<int16_t>(value);
					}
				}
			}
		}

		header.segmentationTreeProbs = segmentationTreeProbs;
		header.segmentationPredProbs = segmentationPredProbs;

		// Tile information.
		const uint32_t miColumns = (header.width + 7) >> 3;
		const uint32_t sb64Columns = (miColumns + 7) >> 3;

		uint8_t minLog2 = 0;

		while ((kMaxTileWidthB64 << minLog2) < sb64Columns) {
			minLog2++;
		}

		uint8_t maxLog2 = 1;

		while ((sb64Columns >> maxLog2) >= kMinTileWidthB64) {
			maxLog2++;
		}

		maxLog2--;

		header.log2TileColumns = minLog2;

		while (header.log2TileColumns < maxLog2 && reader.Read<bool>()) {
			header.log2TileColumns++;
		}

		header.log2TileRows = reader.Read<bool>() ? (reader.Read<bool>() ? 2 : 1) : 0;

		header.compressedHeaderSize = reader.Read<uint16_t>();

		if (!reader.Ok() || header.compressedHeaderSize == 0) {
			return std::nullopt;
		}

		// The uncompressed header ends on a whole byte.
		const size_t bitsRead = frame.size() * 8 - static_cast<size_t>(reader.RemainingBitCount());
		header.uncompressedHeaderSize = static_cast<uint32_t>((bitsRead + 7) / 8);

		if (header.uncompressedHeaderSize + header.compressedHeaderSize > frame.size()) {
			return std::nullopt;
		}

		ComputeSegments(header, yDcDelta, uvDcDelta, uvAcDelta);

		for (int slot = 0; slot < 8; slot++) {
			if (header.refreshFrameFlags & (1 << slot)) {
				slotWidths[slot] = header.width;
				slotHeights[slot] = header.height;
			}
		}

		return header;
	}

	void Vp9FrameHeaderParser::ComputeSegments(Vp9FrameHeader & header, int8_t yDcDelta, int8_t uvDcDelta,
		int8_t uvAcDelta) const
	{
		// The loop filter deltas count double from level 32 on.
		const int scale = 1 << (header.filterLevel >> 5);

		for (int segment = 0; segment < 8; segment++) {
			Vp9Segment & target = header.segments[segment];

			auto active = [&](int feature) {
				return header.segmentationEnabled && featureEnabled[segment][feature];
			};

			target.referenceEnabled = active(kFeatureReference);
			target.reference = static_cast<uint8_t>(featureData[segment][kFeatureReference]);
			target.skipped = active(kFeatureSkip);

			int qIndex = header.baseQIndex;

			if (active(kFeatureQuantizer)) {
				const int data = featureData[segment][kFeatureQuantizer];

				qIndex = std::clamp(segmentationAbsoluteValues ? data : qIndex + data, 0, kMaxQIndex);
			}

			target.lumaDcScale = vp9_dc_quant(qIndex, yDcDelta, 8);
			target.lumaAcScale = vp9_ac_quant(qIndex, 0, 8);
			target.chromaDcScale = vp9_dc_quant(qIndex, uvDcDelta, 8);
			target.chromaAcScale = vp9_ac_quant(qIndex, uvAcDelta, 8);

			int level = header.filterLevel;

			if (active(kFeatureLoopFilter)) {
				const int data = featureData[segment][kFeatureLoopFilter];

				level = ClampLevel(segmentationAbsoluteValues ? data : level + data);
			}

			if (!loopFilterDeltaEnabled) {
				for (auto & modes : target.filterLevel) {
					modes[0] = static_cast<uint8_t>(level);
					modes[1] = static_cast<uint8_t>(level);
				}

				continue;
			}

			// Intra blocks have no mode delta.
			const uint8_t intraLevel = ClampLevel(level + loopFilterRefDeltas[0] * scale);
			target.filterLevel[0][0] = intraLevel;
			target.filterLevel[0][1] = intraLevel;

			for (int reference = 1; reference < 4; reference++) {
				for (int mode = 0; mode < 2; mode++) {
					target.filterLevel[reference][mode] = ClampLevel(level + loopFilterRefDeltas[reference] * scale
						+ loopFilterModeDeltas[mode] * scale);
				}
			}
		}
	}

	std::vector<std::span<const uint8_t>> Vp9FrameHeaderParser::SplitSuperframe(std::span<const uint8_t> data)
	{
		std::vector<std::span<const uint8_t>> frames;

		if (data.empty()) {
			return frames;
		}

		// A superframe ends in an index: a marker byte, the sizes of its
		// frames, and the marker again.
		const uint8_t marker = data.back();

		if ((marker & 0xE0) == 0xC0) {
			const size_t count = (marker & 0x07) + 1;
			const size_t sizeBytes = ((marker >> 3) & 0x03) + 1;
			const size_t indexSize = 2 + sizeBytes * count;

			if (data.size() >= indexSize && data[data.size() - indexSize] == marker) {
				const size_t end = data.size() - indexSize;
				size_t entry = end + 1;
				size_t offset = 0;

				for (size_t i = 0; i < count; i++) {
					size_t size = 0;

					// Little endian.
					for (size_t b = 0; b < sizeBytes; b++) {
						size |= static_cast<size_t>(data[entry++]) << (8 * b);
					}

					if (size > end - offset) {
						return {};
					}
					if (size > 0) {
						frames.push_back(data.subspan(offset, size));
					}

					offset += size;
				}

				return frames;
			}
		}

		frames.push_back(data);

		return frames;
	}
}
