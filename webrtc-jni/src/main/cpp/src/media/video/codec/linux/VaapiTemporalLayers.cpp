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

#include "media/video/codec/linux/VaapiTemporalLayers.h"

#include <algorithm>

namespace jni
{
	namespace
	{
		// The layers of the frames after a key frame, as
		// VAEncMiscParameterTemporalLayerStructure takes them, of L1T2 and
		// L1T3 as WebRTC's structures make them: L1T3 is 0, 2, 1, 2.
		const std::vector<uint32_t> kL1T2Pattern = { 1, 0 };
		const std::vector<uint32_t> kL1T3Pattern = { 2, 1, 2, 0 };

		// The share of the bitrate up to each layer until WebRTC gives its
		// own, as WebRTC splits it for temporal layers.
		constexpr double kL1T2Shares[] = { 0.6, 1.0 };
		constexpr double kL1T3Shares[] = { 0.4, 0.6, 1.0 };
	}

	VaapiTemporalLayers::VaapiTemporalLayers() :
		count(1),
		layerRateControl(false)
	{
		slots.fill(-1);
	}

	bool VaapiTemporalLayers::LayerRateControl(VaapiLibrary & library, VAProfile profile, VAEntrypoint entrypoint,
		int layers)
	{
		VAConfigAttrib attribute = {};
		attribute.type = VAConfigAttribEncRateControlExt;

		if (library.Api().GetConfigAttributes(library.Display(), profile, entrypoint, &attribute, 1)
			!= VA_STATUS_SUCCESS || attribute.value == VA_ATTRIB_NOT_SUPPORTED)
		{
			return false;
		}

		VAConfigAttribValEncRateControlExt extension;
		extension.value = attribute.value;

		return extension.bits.temporal_layer_bitrate_control_flag
			&& static_cast<int>(extension.bits.max_num_temporal_layers_minus1) + 1 >= layers;
	}

	bool VaapiTemporalLayers::Configure(webrtc::ScalabilityMode mode, uint32_t bitrateBps, bool layerRates)
	{
		switch (mode) {
			case webrtc::ScalabilityMode::kL1T1:
				count = 1;
				break;
			case webrtc::ScalabilityMode::kL1T2:
				count = 2;
				break;
			case webrtc::ScalabilityMode::kL1T3:
				count = 3;
				break;
			default:
				return false;
		}

		slots.fill(-1);
		layerRateControl = layerRates;
		bitratesBps.clear();

		for (int layer = 0; layer < count && count > 1; layer++) {
			const double share = count == 3 ? kL1T3Shares[layer] : kL1T2Shares[layer];
			bitratesBps.push_back(static_cast<uint32_t>(bitrateBps * share));
		}

		return true;
	}

	int VaapiTemporalLayers::Count() const
	{
		return count;
	}

	std::optional<VaapiTemporalLayers::Frame> VaapiTemporalLayers::Plan(bool keyFrame,
		const webrtc::ScalableVideoController::LayerFrameConfig & layerFrame) const
	{
		Frame frame;
		frame.temporalId = layerFrame.TemporalId();

		for (int surface = 0; surface < kReconstructedSurfaces && frame.surface < 0; surface++) {
			if (std::find(slots.begin(), slots.end(), surface) == slots.end()) {
				frame.surface = surface;
			}
		}

		if (frame.surface < 0) {
			return std::nullopt;
		}

		for (const webrtc::CodecBufferUsage & buffer : layerFrame.Buffers()) {
			if (buffer.updated && buffer.id >= 0 && buffer.id < static_cast<int>(slots.size())) {
				frame.keptFlags |= static_cast<uint8_t>(1 << buffer.id);
			}
		}

		// A key frame replaces every slot.
		if (keyFrame) {
			frame.refreshFlags = 0xFF;
			return frame;
		}

		for (const webrtc::CodecBufferUsage & buffer : layerFrame.Buffers()) {
			if (buffer.id < 0 || buffer.id >= static_cast<int>(slots.size())) {
				return std::nullopt;
			}
			if (buffer.referenced) {
				if (slots[buffer.id] < 0) {
					return std::nullopt;
				}

				frame.references.push_back(buffer.id);
			}
			if (buffer.updated) {
				frame.refreshFlags |= static_cast<uint8_t>(1 << buffer.id);
			}
		}

		if (frame.references.empty()) {
			return std::nullopt;
		}

		return frame;
	}

	void VaapiTemporalLayers::Commit(const Frame & frame)
	{
		for (int slot = 0; slot < static_cast<int>(slots.size()); slot++) {
			if (frame.keptFlags & (1 << slot)) {
				slots[slot] = frame.surface;
			}
			else if (frame.refreshFlags == 0xFF) {
				slots[slot] = -1;
			}
		}
	}

	void VaapiTemporalLayers::Forget(int slot)
	{
		slots[slot] = -1;
	}

	int VaapiTemporalLayers::SlotCount() const
	{
		return static_cast<int>(slots.size());
	}

	int VaapiTemporalLayers::Surface(int slot) const
	{
		return slots[slot];
	}

	void VaapiTemporalLayers::OnRatesUpdated(const webrtc::VideoBitrateAllocation & bitrates)
	{
		uint32_t sum = 0;

		for (int layer = 0; layer < static_cast<int>(bitratesBps.size()); layer++) {
			sum += bitrates.GetBitrate(0, layer);
			// A layer WebRTC gives no bitrate is not encoded; it keeps the
			// rate of the one below, which the driver takes.
			bitratesBps[layer] = std::max(sum, 1u);
		}
	}

	bool VaapiTemporalLayers::AddRateControl(VaapiEncodeSession & session, uint32_t bitrateBps, uint32_t framerate,
		bool keyFrame) const
	{
		if (count == 1 || !layerRateControl) {
			return session.AddRateControl(bitrateBps, framerate, 0);
		}

		return session.AddLayerRateControl(bitratesBps, framerate, count == 3 ? kL1T3Pattern : kL1T2Pattern,
			keyFrame);
	}

	void VaapiTemporalLayers::FillFpsAllocation(webrtc::VideoEncoder::EncoderInfo & info) const
	{
		if (count == 1) {
			return;
		}

		for (int layer = 0; layer < count; layer++) {
			info.fps_allocation[0].push_back(static_cast<uint8_t>(
				webrtc::VideoEncoder::EncoderInfo::kMaxFramerateFraction >> (count - 1 - layer)));
		}
	}
}
