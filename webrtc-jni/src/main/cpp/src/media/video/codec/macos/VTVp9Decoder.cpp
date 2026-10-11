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

#include "media/video/codec/macos/VTVp9Decoder.h"
#include "media/video/codec/macos/VTCodecUtils.h"

#include "modules/video_coding/include/video_error_codes.h"
#include "rtc_base/logging.h"

#include <span>

namespace jni
{
	namespace
	{
		// The sizes the hardware decoder is used for. Outside them the
		// software decoder takes over. Chromium uses the same limits for
		// VP9 on VideoToolbox; the lower one is that of Apple silicon.
		constexpr int kMinSize = 64;
		constexpr int kMaxSize = 4096;

		// The colour code points of a VP9 colour space, for the VP9
		// configuration box and the format description.
		struct ColorCodePoints
		{
			uint8_t primaries;
			uint8_t transfer;
			uint8_t matrix;
		};

		ColorCodePoints GetColorCodePoints(webrtc::Vp9ColorSpace colorSpace)
		{
			switch (colorSpace) {
				case webrtc::Vp9ColorSpace::CS_BT_601:
				case webrtc::Vp9ColorSpace::CS_SMPTE_170:
					return { 6, 6, 6 };

				case webrtc::Vp9ColorSpace::CS_BT_709:
					return { 1, 1, 1 };

				case webrtc::Vp9ColorSpace::CS_SMPTE_240:
					return { 7, 7, 7 };

				case webrtc::Vp9ColorSpace::CS_BT_2020:
					return { 9, 14, 9 };

				default:
					// Not signalled in the stream: unspecified.
					return { 2, 2, 2 };
			}
		}
	}

	VTVp9Decoder::VTVp9Decoder() :
		VTVideoDecoder("VideoToolbox (VP9)", true)
	{
	}

	bool VTVp9Decoder::Configure(const Settings & settings)
	{
		if (settings.codec_type() != webrtc::kVideoCodecVP9) {
			return false;
		}

		Release();

		// The session waits for the first key frame, which tells what to
		// create it for.
		return true;
	}

	int32_t VTVp9Decoder::Decode(const webrtc::EncodedImage & image, int64_t renderTimeMs)
	{
		if (image.size() == 0) {
			return WEBRTC_VIDEO_CODEC_ERR_PARAMETER;
		}

		// A frame with spatial layers reaches the decoder with its layers
		// back to back and no superframe index, which VideoToolbox does not
		// decode. libvpx does.
		if (image.SpatialIndex().value_or(0) > 0 || image.SpatialLayerFrameSize(1).has_value()) {
			return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
		}

		const std::span<const uint8_t> data(image.data(), image.size());

		// The parser gives a header only for a frame that states its size:
		// key frames, and inter frames that do not take it from a reference.
		// Most inter frames take it, and have none.
		const std::optional<webrtc::Vp9UncompressedHeader> header = webrtc::ParseUncompressedVp9Header(data);

		if (image.FrameType() == webrtc::VideoFrameType::kVideoFrameKey) {
			// A key frame has to state what the session is made for.
			CMVideoFormatDescriptionRef format = header ? CreateFormat(*header) : nullptr;

			if (format == nullptr || !SetFormat(format)) {
				return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
			}

			requireKeyFrame = false;
		}
		else {
			if (!HasSession() || requireKeyFrame) {
				// Nothing to build on: WebRTC asks the sender for a key frame.
				return WEBRTC_VIDEO_CODEC_ERROR;
			}

			// A frame that states another size without being a key frame is
			// reference scaling, which the session does not take. One that
			// takes its size from a reference has no header to check.
			const CMVideoDimensions size = GetDimensions();

			if (header && !header->show_existing_frame
				&& (static_cast<int>(header->frame_width) != size.width
					|| static_cast<int>(header->frame_height) != size.height))
			{
				return WEBRTC_VIDEO_CODEC_FALLBACK_SOFTWARE;
			}
		}

		// The whole encoded image is one sample, hidden frames and all: a
		// frame that is not shown, given to VideoToolbox on its own, would
		// come out as a picture.
		return DecodeSample(data, image, renderTimeMs);
	}

	CMVideoFormatDescriptionRef VTVp9Decoder::CreateFormat(const webrtc::Vp9UncompressedHeader & header)
	{
		// The negotiated format is profile 0, 8 bit 4:2:0. Check, rather than
		// trust it: a stream that is something else is not for this decoder.
		if (header.profile != 0 || header.bit_detph != webrtc::Vp9BitDept::k8Bit) {
			RTC_LOG(LS_INFO) << "VideoToolbox VP9 decoder: profile " << header.profile << " is not decoded in hardware";
			return nullptr;
		}
		if (header.sub_sampling && *header.sub_sampling != webrtc::Vp9YuvSubsampling::k420) {
			return nullptr;
		}

		const int width = header.frame_width;
		const int height = header.frame_height;

		if (width < kMinSize || height < kMinSize || width > kMaxSize || height > kMaxSize) {
			RTC_LOG(LS_INFO) << "VideoToolbox VP9 decoder: " << width << "x" << height
				<< " is not decoded in hardware";
			return nullptr;
		}

		// The range a stream does not state is the studio range, as in libvpx.
		const bool fullRange = header.color_range && *header.color_range == webrtc::Vp9ColorRange::kFull;
		const ColorCodePoints color = GetColorCodePoints(header.color_space.value_or(
			webrtc::Vp9ColorSpace::CS_UNKNOWN));

		// Version 1 and flags 0 come first: VideoToolbox rejects the box
		// without them. Then the profile, a level that covers the sizes in
		// use, the bit depth, 4:2:0 co-located with luma (1) and the range,
		// the colour code points, and no initialization data.
		const uint8_t box[] = {
			1, 0, 0, 0,
			0,
			51,
			static_cast<uint8_t>((8 << 4) | (1 << 1) | (fullRange ? 1 : 0)),
			color.primaries, color.transfer, color.matrix,
			0, 0
		};

		ScopedCF<CFDataRef> boxData(CFDataCreate(kCFAllocatorDefault, box, sizeof(box)));
		ScopedCF<CFMutableDictionaryRef> atoms = CreateCFDictionary();
		ScopedCF<CFMutableDictionaryRef> extensions = CreateCFDictionary();

		if (!boxData || !atoms || !extensions) {
			return nullptr;
		}

		CFDictionarySetValue(atoms.get(), CFSTR("vpcC"), boxData.get());
		CFDictionarySetValue(extensions.get(), kCMFormatDescriptionExtension_SampleDescriptionExtensionAtoms,
			atoms.get());
		CFDictionarySetValue(extensions.get(), kCMFormatDescriptionExtension_FullRangeVideo,
			fullRange ? kCFBooleanTrue : kCFBooleanFalse);

		SetColorExtensions(extensions.get(), color.primaries, color.transfer, color.matrix);

		CMVideoFormatDescriptionRef format = nullptr;
		OSStatus status = CMVideoFormatDescriptionCreate(kCFAllocatorDefault, kCMVideoCodecType_VP9, width,
			height, extensions.get(), &format);

		if (status != noErr) {
			RTC_LOG(LS_WARNING) << "VideoToolbox VP9 format description failed, status " << status;
			return nullptr;
		}

		return format;
	}
}
