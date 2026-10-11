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

#include "media/video/codec/macos/VTH265Decoder.h"

#include "common_video/h265/h265_common.h"
#include "modules/video_coding/include/video_error_codes.h"
#include "rtc_base/logging.h"

#include <span>

namespace jni
{
	namespace
	{
		// The length prefix of each NAL unit in a sample.
		constexpr int kNaluLengthSize = 4;

		bool IsParameterSet(uint8_t type)
		{
			return type == webrtc::H265::NaluType::kVps || type == webrtc::H265::NaluType::kSps
				|| type == webrtc::H265::NaluType::kPps;
		}

		// An IRAP picture (IDR, CRA, BLA), which a decoder can start at.
		bool IsIrap(uint8_t type)
		{
			return type >= webrtc::H265::NaluType::kBlaWLp && type <= webrtc::H265::NaluType::kRsvIrapVcl23;
		}
	}

	VTH265Decoder::VTH265Decoder() :
		VTVideoDecoder("VideoToolbox (H.265)", false)
	{
	}

	bool VTH265Decoder::Configure(const Settings & settings)
	{
		if (settings.codec_type() != webrtc::kVideoCodecH265) {
			return false;
		}

		Release();

		// The session waits for the first key frame, whose parameter sets
		// tell what to create it for.
		return true;
	}

	int32_t VTH265Decoder::Decode(const webrtc::EncodedImage & image, int64_t renderTimeMs)
	{
		if (image.size() == 0) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}

		const std::span<const uint8_t> data(image.data(), image.size());
		const std::vector<webrtc::H265::NaluIndex> nalus = webrtc::H265::FindNaluIndices(data);

		if (nalus.empty()) {
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		std::vector<const uint8_t *> parameterSets;
		std::vector<size_t> parameterSetSizes;
		bool hasVps = false;
		bool hasSps = false;
		bool hasPps = false;
		bool keyFrame = image.FrameType() == webrtc::VideoFrameType::kVideoFrameKey;

		sample.clear();

		for (const webrtc::H265::NaluIndex & nalu : nalus) {
			if (nalu.payload_size == 0) {
				continue;
			}

			const uint8_t * payload = data.data() + nalu.payload_start_offset;
			const uint8_t type = webrtc::H265::ParseNaluType(payload[0]);

			if (IsParameterSet(type)) {
				hasVps |= type == webrtc::H265::NaluType::kVps;
				hasSps |= type == webrtc::H265::NaluType::kSps;
				hasPps |= type == webrtc::H265::NaluType::kPps;

				parameterSets.push_back(payload);
				parameterSetSizes.push_back(nalu.payload_size);

				// The format description holds them; the sample does not.
				continue;
			}
			if (type == webrtc::H265::NaluType::kAud) {
				continue;
			}

			keyFrame |= IsIrap(type);

			const uint32_t size = static_cast<uint32_t>(nalu.payload_size);

			for (int shift = (kNaluLengthSize - 1) * 8; shift >= 0; shift -= 8) {
				sample.push_back(static_cast<uint8_t>(size >> shift));
			}

			sample.insert(sample.end(), payload, payload + nalu.payload_size);
		}

		if (hasVps && hasSps && hasPps) {
			CMVideoFormatDescriptionRef format = nullptr;
			OSStatus status = CMVideoFormatDescriptionCreateFromHEVCParameterSets(kCFAllocatorDefault,
				parameterSets.size(), parameterSets.data(), parameterSetSizes.data(), kNaluLengthSize, nullptr,
				&format);

			if (status != noErr) {
				RTC_LOG(LS_WARNING) << "VideoToolbox H.265 format description failed, status " << status;
				return Unsupported();
			}
			if (!SetFormat(format)) {
				return Unsupported();
			}
		}

		if (!HasSession() || (requireKeyFrame && !keyFrame)) {
			// Nothing to build on: WebRTC asks the sender for a key frame.
			requireKeyFrame = true;
			return WEBRTC_VIDEO_CODEC_ERROR;
		}

		requireKeyFrame = false;

		if (sample.empty()) {
			// Parameter sets alone have no picture.
			return WEBRTC_VIDEO_CODEC_OK;
		}

		return DecodeSample(sample, image, renderTimeMs);
	}
}
