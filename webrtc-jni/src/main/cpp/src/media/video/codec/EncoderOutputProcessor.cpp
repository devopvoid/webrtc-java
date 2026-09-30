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

#include "media/video/codec/EncoderOutputProcessor.h"

#include "api/video/render_resolution.h"
#include "api/video_codecs/scalability_mode.h"
#include "common_video/h264/h264_common.h"
#include "modules/video_coding/codecs/interface/common_constants.h"
#include "rtc_base/logging.h"

namespace jni
{
	namespace
	{
		// The QP thresholds of WebRTC's own H.264 encoder.
		constexpr int kLowH264QpThreshold = 24;
		constexpr int kHighH264QpThreshold = 37;

		// AV1 OBU types, section 6.2.2 of the AV1 specification.
		constexpr uint8_t kObuSequenceHeader = 1;
		constexpr uint8_t kObuTemporalDelimiter = 2;

		struct Obu
		{
			uint8_t type;
			// The whole OBU, header included.
			size_t offset;
			size_t size;
		};

		// Reads a leb128 value, section 4.10.5 of the AV1 specification.
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

				if (extension) {
					position++;
				}

				uint64_t payloadSize = 0;

				if (hasSize) {
					if (!ReadLeb128(data, position, payloadSize)) {
						return false;
					}
				}
				else {
					payloadSize = position <= data.size() ? data.size() - position : 0;
				}

				if (position > data.size() || payloadSize > data.size() - position) {
					return false;
				}

				position += static_cast<size_t>(payloadSize);

				obus.push_back(Obu { type, offset, position - offset });
			}

			return true;
		}
	}

	EncoderOutputProcessor::EncoderOutputProcessor(webrtc::VideoCodecType codec, const webrtc::SdpVideoFormat & format) :
		codec(codec),
		packetizationMode(webrtc::H264PacketizationMode::NonInterleaved)
	{
		auto mode = format.parameters.find("packetization-mode");

		if (mode == format.parameters.end() || mode->second != "1") {
			packetizationMode = webrtc::H264PacketizationMode::SingleNalUnit;
		}
	}

	void EncoderOutputProcessor::Reset()
	{
		parameterSets.clear();
	}

	bool EncoderOutputProcessor::Process(std::span<const uint8_t> bitstream, bool keyFrame,
		webrtc::EncodedImage & image, webrtc::CodecSpecificInfo & info)
	{
		std::vector<uint8_t> output;

		const bool processed = codec == webrtc::kVideoCodecAV1
			? ProcessAv1(bitstream, keyFrame, output)
			: ProcessH264(bitstream, keyFrame, output);

		if (!processed) {
			return false;
		}

		image.SetEncodedData(webrtc::EncodedImageBuffer::Create(output.data(), output.size()));
		image.set_frame_type(keyFrame
			? webrtc::VideoFrameType::kVideoFrameKey
			: webrtc::VideoFrameType::kVideoFrameDelta);

		if (codec == webrtc::kVideoCodecH264) {
			h264Parser.ParseBitstream(std::span<const uint8_t>(output.data(), output.size()));
			image.qp_ = h264Parser.GetLastSliceQp().value_or(-1);
		}
		else {
			image.qp_ = -1;
		}

		FillCodecSpecificInfo(image, keyFrame, info);

		return true;
	}

	bool EncoderOutputProcessor::ProcessH264(std::span<const uint8_t> bitstream, bool & keyFrame,
		std::vector<uint8_t> & output)
	{
		std::vector<uint8_t> parameterSetsFound;
		bool hasSps = false;
		bool hasPps = false;

		const std::vector<webrtc::H264::NaluIndex> nalus = webrtc::H264::FindNaluIndices(bitstream);

		if (nalus.empty()) {
			return false;
		}

		for (const webrtc::H264::NaluIndex & nalu : nalus) {
			const size_t end = nalu.payload_start_offset + nalu.payload_size;
			const webrtc::H264::NaluType type = webrtc::H264::ParseNaluType(bitstream[nalu.payload_start_offset]);

			if (type == webrtc::H264::NaluType::kSps || type == webrtc::H264::NaluType::kPps) {
				hasSps |= type == webrtc::H264::NaluType::kSps;
				hasPps |= type == webrtc::H264::NaluType::kPps;

				parameterSetsFound.insert(parameterSetsFound.end(),
					bitstream.begin() + nalu.start_offset, bitstream.begin() + end);
			}
			else if (type == webrtc::H264::NaluType::kIdr) {
				keyFrame = true;
			}
		}

		if (hasSps && hasPps) {
			parameterSets = std::move(parameterSetsFound);
		}
		else if (keyFrame) {
			if (parameterSets.empty()) {
				RTC_LOG(LS_WARNING) << "H.264 encoder produced a key frame without SPS and PPS";
				return false;
			}

			output.insert(output.end(), parameterSets.begin(), parameterSets.end());
		}

		output.insert(output.end(), bitstream.begin(), bitstream.end());

		return true;
	}

	bool EncoderOutputProcessor::ProcessAv1(std::span<const uint8_t> bitstream, bool keyFrame,
		std::vector<uint8_t> & output)
	{
		std::vector<Obu> obus;

		if (!ParseObus(bitstream, obus) || obus.empty()) {
			RTC_LOG(LS_WARNING) << "AV1 encoder produced a bitstream that is not in the low overhead format";
			return false;
		}

		bool hasSequenceHeader = false;

		for (const Obu & obu : obus) {
			if (obu.type == kObuSequenceHeader) {
				hasSequenceHeader = true;
				parameterSets.assign(bitstream.begin() + obu.offset, bitstream.begin() + obu.offset + obu.size);
			}
		}

		if (!keyFrame || hasSequenceHeader) {
			output.assign(bitstream.begin(), bitstream.end());
			return true;
		}

		if (parameterSets.empty()) {
			RTC_LOG(LS_WARNING) << "AV1 encoder produced a key frame without a sequence header";
			return false;
		}

		// A temporal delimiter has to stay first in its temporal unit.
		size_t start = 0;

		if (obus.front().type == kObuTemporalDelimiter) {
			start = obus.front().size;
			output.insert(output.end(), bitstream.begin(), bitstream.begin() + start);
		}

		output.insert(output.end(), parameterSets.begin(), parameterSets.end());
		output.insert(output.end(), bitstream.begin() + start, bitstream.end());

		return true;
	}

	void EncoderOutputProcessor::FillCodecSpecificInfo(const webrtc::EncodedImage & image, bool keyFrame,
		webrtc::CodecSpecificInfo & info)
	{
		info.codecType = codec;

		if (codec == webrtc::kVideoCodecH264) {
			info.codecSpecific.H264.packetization_mode = packetizationMode;
			info.codecSpecific.H264.temporal_idx = webrtc::kNoTemporalIdx;
			info.codecSpecific.H264.base_layer_sync = false;
			info.codecSpecific.H264.idr_frame = keyFrame;
			return;
		}

		// AV1 has no codec specific information; WebRTC sends its frame
		// dependencies from these, in the dependency descriptor.
		auto layerFrames = svcController.NextFrameConfig(/*restart=*/keyFrame);
		info.generic_frame_info = svcController.OnEncodeDone(layerFrames[0]);
		info.scalability_mode = webrtc::ScalabilityMode::kL1T1;

		if (keyFrame) {
			info.template_structure = svcController.DependencyStructure();
			info.template_structure->resolutions = {
				webrtc::RenderResolution(image._encodedWidth, image._encodedHeight)
			};
		}
	}

	webrtc::VideoEncoder::ScalingSettings EncoderOutputProcessor::GetScalingSettings() const
	{
		if (codec == webrtc::kVideoCodecH264) {
			return webrtc::VideoEncoder::ScalingSettings(kLowH264QpThreshold, kHighH264QpThreshold);
		}

		return webrtc::VideoEncoder::ScalingSettings::kOff;
	}
}
