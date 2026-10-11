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

#include "media/video/codec/macos/VTAv1Decoder.h"
#include "media/video/codec/macos/VTCodecUtils.h"

#include "modules/video_coding/include/video_error_codes.h"
#include "rtc_base/bitstream_reader.h"
#include "rtc_base/logging.h"

#include <algorithm>

namespace jni
{
	namespace
	{
		// OBU types, section 6.2.2 of the AV1 specification.
		constexpr uint8_t kObuSequenceHeader = 1;
		constexpr uint8_t kObuTemporalDelimiter = 2;
		constexpr uint8_t kObuTileList = 8;
		constexpr uint8_t kObuPadding = 15;

		struct Obu
		{
			uint8_t type;
			uint8_t spatialId;
			// The header, with the extension if there is one, without the
			// size field.
			std::span<const uint8_t> header;
			std::span<const uint8_t> payload;
		};

		bool ReadLeb128(std::span<const uint8_t> data, size_t & position, uint64_t & value)
		{
			value = 0;

			for (int i = 0; i < 8; i++) {
				if (position >= data.size()) {
					return false;
				}

				const uint8_t byte = data[position++];

				value |= static_cast<uint64_t>(byte & 0x7F) << (i * 7);

				if (!(byte & 0x80)) {
					return true;
				}
			}

			return false;
		}

		void WriteLeb128(uint64_t value, std::vector<uint8_t> & output)
		{
			do {
				uint8_t byte = value & 0x7F;
				value >>= 7;

				output.push_back(value != 0 ? (byte | 0x80) : byte);
			}
			while (value != 0);
		}

		// Splits a temporal unit into its OBUs, section 5.3 of the AV1
		// specification. An OBU without a size field runs to the end.
		bool ParseObus(std::span<const uint8_t> data, std::vector<Obu> & obus)
		{
			size_t position = 0;

			while (position < data.size()) {
				const size_t offset = position;
				const uint8_t header = data[position++];

				// The forbidden bit.
				if (header & 0x80) {
					return false;
				}

				const uint8_t type = (header >> 3) & 0x0F;
				const bool extension = header & 0x04;
				const bool hasSize = header & 0x02;
				uint8_t spatialId = 0;

				if (extension) {
					if (position >= data.size()) {
						return false;
					}

					spatialId = (data[position++] >> 3) & 0x03;
				}

				const size_t headerSize = position - offset;
				uint64_t payloadSize = 0;

				if (hasSize) {
					if (!ReadLeb128(data, position, payloadSize)) {
						return false;
					}
				}
				else {
					payloadSize = data.size() - position;
				}

				if (payloadSize > data.size() - position) {
					return false;
				}

				obus.push_back(Obu {
					type,
					spatialId,
					data.subspan(offset, headerSize),
					data.subspan(position, static_cast<size_t>(payloadSize))
				});

				position += static_cast<size_t>(payloadSize);
			}

			return true;
		}

		// Writes an OBU with its size field, which ISOBMFF wants on every OBU
		// of a sample.
		void WriteObu(const Obu & obu, std::vector<uint8_t> & output)
		{
			output.push_back(obu.header[0] | 0x02);
			output.insert(output.end(), obu.header.begin() + 1, obu.header.end());

			WriteLeb128(obu.payload.size(), output);

			output.insert(output.end(), obu.payload.begin(), obu.payload.end());
		}

		// What the configuration box and the format description need of a
		// sequence header, section 5.5 of the AV1 specification.
		struct SequenceHeader
		{
			uint8_t profile = 0;
			uint8_t level = 0;
			uint8_t tier = 0;
			bool highBitDepth = false;
			bool twelveBit = false;
			bool monochrome = false;
			bool subsamplingX = true;
			bool subsamplingY = true;
			uint8_t chromaSamplePosition = 0;
			bool fullRange = false;
			uint8_t primaries = 2;
			uint8_t transfer = 2;
			uint8_t matrix = 2;
			uint32_t maxWidth = 0;
			uint32_t maxHeight = 0;
			// The spatial layers of the first operating point.
			int spatialLayers = 1;
		};

		uint32_t ReadUvlc(webrtc::BitstreamReader & reader)
		{
			int leadingZeros = 0;

			while (reader.Ok() && reader.ReadBit() == 0) {
				if (++leadingZeros >= 32) {
					reader.Invalidate();
					return 0;
				}
			}

			return leadingZeros == 0 ? 0 : static_cast<uint32_t>(reader.ReadBits(leadingZeros) + (1ull << leadingZeros) - 1);
		}

		bool ParseSequenceHeader(std::span<const uint8_t> payload, SequenceHeader & sequence)
		{
			webrtc::BitstreamReader reader(payload);

			sequence.profile = reader.ReadBits(3);
			reader.ConsumeBits(1);  // still_picture

			const bool reducedStillPictureHeader = reader.ReadBit();
			bool decoderModelInfoPresent = false;
			int bufferDelayLength = 0;

			if (reducedStillPictureHeader) {
				sequence.level = reader.ReadBits(5);
			}
			else {
				const bool timingInfoPresent = reader.ReadBit();

				if (timingInfoPresent) {
					// num_units_in_display_tick, time_scale
					reader.ConsumeBits(64);

					if (reader.ReadBit()) {
						// num_ticks_per_picture_minus_1
						(void) ReadUvlc(reader);
					}

					decoderModelInfoPresent = reader.ReadBit();

					if (decoderModelInfoPresent) {
						bufferDelayLength = reader.ReadBits(5) + 1;
						// num_units_in_decoding_tick, buffer_removal_time_length_minus_1,
						// frame_presentation_time_length_minus_1
						reader.ConsumeBits(32 + 5 + 5);
					}
				}

				const bool initialDisplayDelayPresent = reader.ReadBit();
				const int operatingPoints = reader.ReadBits(5) + 1;

				for (int i = 0; i < operatingPoints; i++) {
					const uint32_t idc = reader.ReadBits(12);
					const uint8_t level = reader.ReadBits(5);
					const uint8_t tier = level > 7 ? reader.ReadBit() : 0;

					if (decoderModelInfoPresent && reader.ReadBit()) {
						// decoder_buffer_delay, encoder_buffer_delay, low_delay_mode_flag
						reader.ConsumeBits(2 * bufferDelayLength + 1);
					}
					if (initialDisplayDelayPresent && reader.ReadBit()) {
						reader.ConsumeBits(4);
					}

					if (i == 0) {
						sequence.level = level;
						sequence.tier = tier;
						// The spatial layers are bits 8 to 11 of the idc.
						sequence.spatialLayers = std::max(1, __builtin_popcount((idc >> 8) & 0x0F));
					}
				}
			}

			const int widthBits = reader.ReadBits(4) + 1;
			const int heightBits = reader.ReadBits(4) + 1;

			sequence.maxWidth = static_cast<uint32_t>(reader.ReadBits(widthBits)) + 1;
			sequence.maxHeight = static_cast<uint32_t>(reader.ReadBits(heightBits)) + 1;

			if (!reducedStillPictureHeader && reader.ReadBit()) {
				// delta_frame_id_length_minus_2, additional_frame_id_length_minus_1
				reader.ConsumeBits(4 + 3);
			}

			// use_128x128_superblock, enable_filter_intra, enable_intra_edge_filter
			reader.ConsumeBits(3);

			if (!reducedStillPictureHeader) {
				// enable_interintra_compound, enable_masked_compound,
				// enable_warped_motion, enable_dual_filter
				reader.ConsumeBits(4);

				const bool enableOrderHint = reader.ReadBit();

				if (enableOrderHint) {
					// enable_jnt_comp, enable_ref_frame_mvs
					reader.ConsumeBits(2);
				}

				const bool chooseScreenContentTools = reader.ReadBit();
				int forceScreenContentTools = 2;

				if (!chooseScreenContentTools) {
					forceScreenContentTools = reader.ReadBit();
				}
				if (forceScreenContentTools > 0) {
					// seq_choose_integer_mv, and seq_force_integer_mv without it
					if (!reader.ReadBit()) {
						reader.ConsumeBits(1);
					}
				}
				if (enableOrderHint) {
					reader.ConsumeBits(3);
				}
			}

			// enable_superres, enable_cdef, enable_restoration
			reader.ConsumeBits(3);

			// color_config
			sequence.highBitDepth = reader.ReadBit();

			if (sequence.profile == 2 && sequence.highBitDepth) {
				sequence.twelveBit = reader.ReadBit();
			}

			sequence.monochrome = sequence.profile == 1 ? false : reader.ReadBit();

			if (reader.ReadBit()) {
				sequence.primaries = reader.ReadBits(8);
				sequence.transfer = reader.ReadBits(8);
				sequence.matrix = reader.ReadBits(8);
			}

			if (sequence.monochrome) {
				sequence.fullRange = reader.ReadBit();
			}
			else if (sequence.primaries == 1 && sequence.transfer == 13 && sequence.matrix == 0) {
				// sRGB: full range 4:4:4.
				sequence.fullRange = true;
				sequence.subsamplingX = false;
				sequence.subsamplingY = false;
			}
			else {
				sequence.fullRange = reader.ReadBit();

				if (sequence.profile == 1) {
					sequence.subsamplingX = false;
					sequence.subsamplingY = false;
				}
				else if (sequence.profile == 2) {
					if (sequence.twelveBit) {
						sequence.subsamplingX = reader.ReadBit();
						sequence.subsamplingY = sequence.subsamplingX ? reader.ReadBit() : false;
					}
					else {
						sequence.subsamplingX = true;
						sequence.subsamplingY = false;
					}
				}

				if (sequence.subsamplingX && sequence.subsamplingY) {
					sequence.chromaSamplePosition = reader.ReadBits(2);
				}
			}

			return reader.Ok();
		}
	}

	VTAv1Decoder::VTAv1Decoder() :
		VTVideoDecoder("VideoToolbox (AV1)", true)
	{
	}

	bool VTAv1Decoder::Configure(const Settings & settings)
	{
		if (settings.codec_type() != webrtc::kVideoCodecAV1) {
			return false;
		}

		Release();

		// The session waits for the first key frame, whose sequence header
		// tells what to create it for.
		return true;
	}

	int32_t VTAv1Decoder::Decode(const webrtc::EncodedImage & image, int64_t renderTimeMs)
	{
		if (image.size() == 0) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}

		std::vector<Obu> obus;

		if (!ParseObus(std::span<const uint8_t>(image.data(), image.size()), obus) || obus.empty()) {
			return Fail(kVTVideoDecoderBadDataErr);
		}

		const bool keyFrame = image.FrameType() == webrtc::VideoFrameType::kVideoFrameKey;
		std::vector<uint8_t> sequenceHeader;

		sample.clear();

		for (const Obu & obu : obus) {
			if (obu.type == kObuTemporalDelimiter || obu.type == kObuTileList || obu.type == kObuPadding) {
				continue;
			}
			// The frames of the higher spatial layers of a temporal unit are
			// left to dav1d.
			if (obu.spatialId > 0) {
				return Unsupported();
			}
			if (obu.type == kObuSequenceHeader && sequenceHeader.empty()) {
				WriteObu(obu, sequenceHeader);
			}

			WriteObu(obu, sample);
		}

		if (keyFrame) {
			// A key frame without a sequence header goes on with the session
			// there is.
			if (!sequenceHeader.empty()) {
				CMVideoFormatDescriptionRef format = CreateFormat(sequenceHeader);

				if (format == nullptr || !SetFormat(format)) {
					return Unsupported();
				}
			}
			if (HasSession()) {
				requireKeyFrame = false;
			}
		}

		if (!HasSession() || requireKeyFrame) {
			// Nothing to build on: WebRTC asks the sender for a key frame.
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		return DecodeSample(sample, image, renderTimeMs);
	}

	CMVideoFormatDescriptionRef VTAv1Decoder::CreateFormat(std::span<const uint8_t> sequenceHeaderObu)
	{
		std::vector<Obu> obus;
		SequenceHeader sequence;

		if (!ParseObus(sequenceHeaderObu, obus) || obus.size() != 1
			|| !ParseSequenceHeader(obus[0].payload, sequence))
		{
			RTC_LOG(LS_WARNING) << "VideoToolbox AV1 decoder: the sequence header cannot be parsed";
			return nullptr;
		}

		// The negotiated format is profile 0. Check, rather than trust it, and
		// leave what the output format does not hold to dav1d.
		if (sequence.profile != 0 || sequence.highBitDepth || sequence.monochrome) {
			RTC_LOG(LS_INFO) << "VideoToolbox AV1 decoder: profile " << static_cast<int>(sequence.profile)
				<< (sequence.highBitDepth ? ", high bit depth" : "")
				<< (sequence.monochrome ? ", monochrome" : "") << " is not decoded in hardware";
			return nullptr;
		}
		if (sequence.spatialLayers > 1) {
			RTC_LOG(LS_INFO) << "VideoToolbox AV1 decoder: spatial layers are not decoded in hardware";
			return nullptr;
		}

		// The AV1 codec configuration box, section 2.3 of the AV1 ISOBMFF
		// binding: marker and version 1, the profile and level, the tier,
		// bit depth and chroma format, no initial presentation delay, and the
		// sequence header as its configuration OBU.
		std::vector<uint8_t> box = {
			0x81,
			static_cast<uint8_t>((sequence.profile << 5) | (sequence.level & 0x1F)),
			static_cast<uint8_t>((sequence.tier << 7) | (sequence.highBitDepth << 6) | (sequence.twelveBit << 5)
				| (sequence.monochrome << 4) | (sequence.subsamplingX << 3) | (sequence.subsamplingY << 2)
				| (sequence.chromaSamplePosition & 0x03)),
			0
		};
		box.insert(box.end(), sequenceHeaderObu.begin(), sequenceHeaderObu.end());

		ScopedCF<CFDataRef> boxData(CFDataCreate(kCFAllocatorDefault, box.data(), box.size()));
		ScopedCF<CFMutableDictionaryRef> atoms = CreateCFDictionary();
		ScopedCF<CFMutableDictionaryRef> extensions = CreateCFDictionary();

		if (!boxData || !atoms || !extensions) {
			return nullptr;
		}

		CFDictionarySetValue(atoms.get(), CFSTR("av1C"), boxData.get());
		CFDictionarySetValue(extensions.get(), kCMFormatDescriptionExtension_SampleDescriptionExtensionAtoms,
			atoms.get());
		CFDictionarySetValue(extensions.get(), kCMFormatDescriptionExtension_FullRangeVideo,
			sequence.fullRange ? kCFBooleanTrue : kCFBooleanFalse);

		SetColorExtensions(extensions.get(), sequence.primaries, sequence.transfer, sequence.matrix);

		// Frames may be smaller than the largest size the sequence allows,
		// which is the size the format describes.
		CMVideoFormatDescriptionRef format = nullptr;
		OSStatus status = CMVideoFormatDescriptionCreate(kCFAllocatorDefault, kCMVideoCodecType_AV1,
			static_cast<int32_t>(sequence.maxWidth), static_cast<int32_t>(sequence.maxHeight), extensions.get(),
			&format);

		if (status != noErr) {
			RTC_LOG(LS_WARNING) << "VideoToolbox AV1 format description failed, status " << status;
			return nullptr;
		}

		return format;
	}
}
