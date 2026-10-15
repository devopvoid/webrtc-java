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
#include "common_video/h265/h265_common.h"
#include "modules/video_coding/codecs/interface/common_constants.h"
#include "modules/video_coding/svc/create_scalability_structure.h"
#include "modules/video_coding/svc/scalability_mode_util.h"
#include "modules/video_coding/svc/scalable_video_controller_no_layering.h"
#include "modules/video_coding/utility/vp9_uncompressed_header_parser.h"
#include "rtc_base/logging.h"

namespace jni
{
	namespace
	{
		// The QP thresholds of WebRTC's own H.264 encoder.
		constexpr int kLowH264QpThreshold = 24;
		constexpr int kHighH264QpThreshold = 37;

		// Those of the Java VP9 encoders, for a QP of 0 to 255.
		constexpr int kLowVp9QpThreshold = 96;
		constexpr int kHighVp9QpThreshold = 185;

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
		packetizationMode(webrtc::H264PacketizationMode::NonInterleaved),
		scalabilityMode(webrtc::ScalabilityMode::kL1T1),
		svcController(std::make_unique<webrtc::ScalableVideoControllerNoLayering>())
	{
		auto mode = format.parameters.find("packetization-mode");

		if (mode == format.parameters.end() || mode->second != "1") {
			packetizationMode = webrtc::H264PacketizationMode::SingleNalUnit;
		}

		gof.SetGofInfoVP9(webrtc::TemporalStructureMode::kTemporalStructureMode1);
	}

	void EncoderOutputProcessor::Reset()
	{
		parameterSets.clear();
		gofIndex = 0;
	}

	bool EncoderOutputProcessor::SetScalabilityMode(webrtc::ScalabilityMode mode)
	{
		std::unique_ptr<webrtc::ScalableVideoController> controller = webrtc::CreateScalabilityStructure(mode);

		if (!controller) {
			return false;
		}

		scalabilityMode = mode;
		svcController = std::move(controller);

		return true;
	}

	void EncoderOutputProcessor::OnRatesUpdated(const webrtc::VideoBitrateAllocation & bitrates)
	{
		svcController->OnRatesUpdated(bitrates);
	}

	std::optional<webrtc::ScalableVideoController::LayerFrameConfig> EncoderOutputProcessor::NextFrameConfig(
		bool keyFrame)
	{
		auto layerFrames = svcController->NextFrameConfig(/*restart=*/keyFrame);

		if (layerFrames.empty()) {
			return std::nullopt;
		}

		return layerFrames[0];
	}

	bool EncoderOutputProcessor::Process(std::span<const uint8_t> bitstream, bool keyFrame,
		webrtc::EncodedImage & image, webrtc::CodecSpecificInfo & info)
	{
		// A stream without layers has a frame for every one encoded.
		auto layerFrame = NextFrameConfig(keyFrame);

		return layerFrame && Process(bitstream, keyFrame, *layerFrame, image, info);
	}

	bool EncoderOutputProcessor::Process(std::span<const uint8_t> bitstream, bool keyFrame,
		const webrtc::ScalableVideoController::LayerFrameConfig & layerFrame, webrtc::EncodedImage & image,
		webrtc::CodecSpecificInfo & info)
	{
		std::vector<uint8_t> output;
		bool processed;

		switch (codec) {
			case webrtc::kVideoCodecAV1:
				processed = ProcessAv1(bitstream, keyFrame, output);
				break;
			case webrtc::kVideoCodecH265:
				processed = ProcessH265(bitstream, keyFrame, output);
				break;
			case webrtc::kVideoCodecVP9:
				// No parameter sets: every key frame carries what it needs.
				output.assign(bitstream.begin(), bitstream.end());
				processed = !output.empty();
				break;
			default:
				processed = ProcessH264(bitstream, keyFrame, output);
				break;
		}

		if (!processed) {
			return false;
		}

		image.SetEncodedData(webrtc::EncodedImageBuffer::Create(output.data(), output.size()));
		// A stream without layers: nothing else follows for this frame, which
		// lets WebRTC release what it keeps of the frame until then.
		image.set_end_of_temporal_unit(true);
		image.set_frame_type(keyFrame
			? webrtc::VideoFrameType::kVideoFrameKey
			: webrtc::VideoFrameType::kVideoFrameDelta);

		const std::span<const uint8_t> encoded(output.data(), output.size());

		if (codec == webrtc::kVideoCodecH264) {
			h264Parser.ParseBitstream(encoded);
			image.qp_ = h264Parser.GetLastSliceQp().value_or(-1);
		}
		else if (codec == webrtc::kVideoCodecH265) {
			h265Parser.ParseBitstream(encoded);
			image.qp_ = h265Parser.GetLastSliceQp().value_or(-1);
		}
		else if (codec == webrtc::kVideoCodecVP9) {
			int qp = -1;
			image.qp_ = webrtc::vp9::GetQp(output.data(), output.size(), &qp) ? qp : -1;
		}
		else {
			image.qp_ = -1;
		}

		if (scalabilityMode != webrtc::ScalabilityMode::kL1T1) {
			image.SetTemporalIndex(layerFrame.TemporalId());
		}

		FillCodecSpecificInfo(image, keyFrame, layerFrame, info);

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

	bool EncoderOutputProcessor::ProcessH265(std::span<const uint8_t> bitstream, bool & keyFrame,
		std::vector<uint8_t> & output)
	{
		std::vector<uint8_t> parameterSetsFound;
		bool hasVps = false;
		bool hasSps = false;
		bool hasPps = false;

		const std::vector<webrtc::H265::NaluIndex> nalus = webrtc::H265::FindNaluIndices(bitstream);

		if (nalus.empty()) {
			return false;
		}

		for (const webrtc::H265::NaluIndex & nalu : nalus) {
			const size_t end = nalu.payload_start_offset + nalu.payload_size;
			const uint8_t type = webrtc::H265::ParseNaluType(bitstream[nalu.payload_start_offset]);

			if (type == webrtc::H265::NaluType::kVps || type == webrtc::H265::NaluType::kSps
				|| type == webrtc::H265::NaluType::kPps)
			{
				hasVps |= type == webrtc::H265::NaluType::kVps;
				hasSps |= type == webrtc::H265::NaluType::kSps;
				hasPps |= type == webrtc::H265::NaluType::kPps;

				parameterSetsFound.insert(parameterSetsFound.end(),
					bitstream.begin() + nalu.start_offset, bitstream.begin() + end);
			}
			else if (type >= webrtc::H265::NaluType::kBlaWLp && type <= webrtc::H265::NaluType::kRsvIrapVcl23) {
				// An IRAP picture (IDR, CRA, BLA), which a decoder can start at.
				keyFrame = true;
			}
		}

		if (hasVps && hasSps && hasPps) {
			parameterSets = std::move(parameterSetsFound);
		}
		else if (keyFrame) {
			if (parameterSets.empty()) {
				RTC_LOG(LS_WARNING) << "H.265 encoder produced a key frame without VPS, SPS and PPS";
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
		const webrtc::ScalableVideoController::LayerFrameConfig & layerFrame, webrtc::CodecSpecificInfo & info)
	{
		info.codecType = codec;

		if (codec == webrtc::kVideoCodecH264) {
			info.codecSpecific.H264.packetization_mode = packetizationMode;
			info.codecSpecific.H264.temporal_idx = webrtc::kNoTemporalIdx;
			info.codecSpecific.H264.base_layer_sync = false;
			info.codecSpecific.H264.idr_frame = keyFrame;

			if (scalabilityMode == webrtc::ScalabilityMode::kL1T1) {
				return;
			}

			// With temporal layers, the layer, and whether an upper layer frame
			// refers to the base layer only, as OpenH264 gives them; the frame
			// dependencies follow below. The base layer is buffer 0 in WebRTC's
			// structures.
			bool baseOnly = true;

			for (const webrtc::CodecBufferUsage & buffer : layerFrame.Buffers()) {
				baseOnly &= !buffer.referenced || buffer.id == 0;
			}

			info.codecSpecific.H264.temporal_idx = static_cast<uint8_t>(layerFrame.TemporalId());
			info.codecSpecific.H264.base_layer_sync = layerFrame.TemporalId() > 0 && baseOnly;
		}

		// AV1 and H.265 have no codec specific information; WebRTC sends
		// their frame dependencies, and those of VP9 and of H.264 with layers,
		// from these, in the dependency descriptor.
		info.generic_frame_info = svcController->OnEncodeDone(layerFrame);
		info.scalability_mode = scalabilityMode;

		if (keyFrame) {
			info.template_structure = svcController->DependencyStructure();
			info.template_structure->resolutions = {
				webrtc::RenderResolution(image._encodedWidth, image._encodedHeight)
			};
		}

		if (codec == webrtc::kVideoCodecVP9 && scalabilityMode != webrtc::ScalabilityMode::kL1T1) {
			FillVp9TemporalLayers(image, keyFrame, layerFrame, info);
		}
		else if (codec == webrtc::kVideoCodecVP9) {
			// VP9 has its own RTP header, which WebRTC fills from these: one
			// spatial and one temporal layer, the group of frames sent with
			// every key frame.
			if (keyFrame) {
				gofIndex = 0;
			}

			info.codecSpecific.VP9.inter_pic_predicted = !keyFrame;
			info.codecSpecific.VP9.flexible_mode = false;
			info.codecSpecific.VP9.ss_data_available = keyFrame;
			info.codecSpecific.VP9.temporal_idx = webrtc::kNoTemporalIdx;
			info.codecSpecific.VP9.temporal_up_switch = true;
			info.codecSpecific.VP9.inter_layer_predicted = false;
			info.codecSpecific.VP9.gof_idx = static_cast<uint8_t>(gofIndex++ % gof.num_frames_in_gof);
			info.codecSpecific.VP9.num_spatial_layers = 1;
			info.codecSpecific.VP9.first_frame_in_picture = true;
			info.codecSpecific.VP9.spatial_layer_resolution_present = keyFrame;

			if (keyFrame) {
				info.codecSpecific.VP9.width[0] = image._encodedWidth;
				info.codecSpecific.VP9.height[0] = image._encodedHeight;
				info.codecSpecific.VP9.gof.CopyGofInfoVP9(gof);
			}
		}
	}

	void EncoderOutputProcessor::FillVp9TemporalLayers(const webrtc::EncodedImage & image, bool keyFrame,
		const webrtc::ScalableVideoController::LayerFrameConfig & layerFrame, webrtc::CodecSpecificInfo & info)
	{
		// One spatial layer and temporal ones, in flexible mode, as libvpx
		// describes them: each picture names the pictures it refers to, by
		// how many pictures back they are.
		vp9Picture = keyFrame ? 0 : vp9Picture + 1;

		webrtc::CodecSpecificInfoVP9 & vp9 = info.codecSpecific.VP9;
		vp9.first_frame_in_picture = true;
		vp9.flexible_mode = true;
		vp9.ss_data_available = keyFrame;
		vp9.non_ref_for_inter_layer_pred = true;
		vp9.temporal_idx = static_cast<uint8_t>(layerFrame.TemporalId());
		vp9.inter_layer_predicted = false;
		vp9.gof_idx = webrtc::kNoGofIdx;
		vp9.num_spatial_layers = 1;
		vp9.first_active_layer = 0;
		vp9.spatial_layer_resolution_present = keyFrame;
		vp9.num_ref_pics = 0;

		if (!keyFrame) {
			for (const webrtc::CodecBufferUsage & buffer : layerFrame.Buffers()) {
				if (!buffer.referenced || buffer.id < 0 || buffer.id >= webrtc::kMaxEncoderBuffers) {
					continue;
				}

				const size_t pDiff = vp9Picture - vp9BufferPictures[buffer.id];
				bool listed = false;

				for (uint8_t i = 0; i < vp9.num_ref_pics; i++) {
					listed |= vp9.p_diff[i] == pDiff;
				}

				if (!listed && pDiff > 0 && vp9.num_ref_pics < webrtc::kMaxVp9RefPics) {
					vp9.p_diff[vp9.num_ref_pics++] = static_cast<uint8_t>(pDiff);
				}
			}
		}

		vp9.inter_pic_predicted = !keyFrame && vp9.num_ref_pics > 0;

		// A switch up to a higher layer is possible where all layers above
		// this one are switch points, as libvpx works it out.
		const int temporalLayers = webrtc::ScalabilityModeToNumTemporalLayers(scalabilityMode);
		vp9.temporal_up_switch = true;

		for (int layer = layerFrame.TemporalId() + 1; layer < temporalLayers && info.generic_frame_info; layer++) {
			const auto & indications = info.generic_frame_info->decode_target_indications;

			vp9.temporal_up_switch &= layer < static_cast<int>(indications.size())
				&& indications[layer] == webrtc::DecodeTargetIndication::kSwitch;
		}

		if (keyFrame) {
			vp9.width[0] = image._encodedWidth;
			vp9.height[0] = image._encodedHeight;
			vp9.gof.num_frames_in_gof = 0;
		}

		for (const webrtc::CodecBufferUsage & buffer : layerFrame.Buffers()) {
			if ((buffer.updated || keyFrame) && buffer.id >= 0 && buffer.id < webrtc::kMaxEncoderBuffers) {
				vp9BufferPictures[buffer.id] = vp9Picture;
			}
		}

		if (keyFrame) {
			vp9BufferPictures.fill(0);
		}
	}

	webrtc::VideoEncoder::ScalingSettings EncoderOutputProcessor::GetScalingSettings() const
	{
		// H.265 has no thresholds of its own in WebRTC yet and uses those of
		// H.264 (bugs.webrtc.org/13485).
		if (codec == webrtc::kVideoCodecH264 || codec == webrtc::kVideoCodecH265) {
			return webrtc::VideoEncoder::ScalingSettings(kLowH264QpThreshold, kHighH264QpThreshold);
		}
		if (codec == webrtc::kVideoCodecVP9) {
			return webrtc::VideoEncoder::ScalingSettings(kLowVp9QpThreshold, kHighVp9QpThreshold);
		}

		return webrtc::VideoEncoder::ScalingSettings::kOff;
	}
}
