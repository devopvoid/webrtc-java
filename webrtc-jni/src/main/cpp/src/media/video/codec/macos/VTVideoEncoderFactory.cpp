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

#include "media/video/codec/macos/VTVideoEncoderFactory.h"
#include "media/video/codec/macos/VTCodecUtils.h"
#include "media/video/codec/macos/VTH265Encoder.h"
#include "media/video/codec/H265Format.h"

#include "rtc_base/logging.h"

#include <VideoToolbox/VideoToolbox.h>

namespace jni
{
	namespace
	{
		// Whether VideoToolbox lists a hardware encoder for H.265. The answer
		// does not change while the process runs, and is asked once.
		bool HasHardwareH265Encoder()
		{
			static const bool available = [] {
				ScopedCF<CFArrayRef> encoders;
				bool found = false;

				if (VTCopyVideoEncoderList(nullptr, encoders.receive()) == noErr && encoders) {
					for (CFIndex i = 0; i < CFArrayGetCount(encoders.get()) && !found; i++) {
						auto encoder = static_cast<CFDictionaryRef>(CFArrayGetValueAtIndex(encoders.get(), i));
						auto codec = static_cast<CFNumberRef>(CFDictionaryGetValue(encoder,
							kVTVideoEncoderList_CodecType));
						auto hardware = static_cast<CFBooleanRef>(CFDictionaryGetValue(encoder,
							kVTVideoEncoderList_IsHardwareAccelerated));
						int32_t type = 0;

						found = codec != nullptr && CFNumberGetValue(codec, kCFNumberSInt32Type, &type)
							&& type == kCMVideoCodecType_HEVC
							&& hardware != nullptr && CFBooleanGetValue(hardware);
					}
				}

				RTC_LOG(LS_INFO) << "VideoToolbox hardware encoder for H.265: " << found;

				return found;
			}();

			return available;
		}
	}

	std::unique_ptr<VTVideoEncoderFactory> VTVideoEncoderFactory::Create()
	{
		if (!HasHardwareH265Encoder()) {
			return nullptr;
		}

		return std::unique_ptr<VTVideoEncoderFactory>(new VTVideoEncoderFactory());
	}

	std::vector<webrtc::SdpVideoFormat> VTVideoEncoderFactory::GetSupportedFormats() const
	{
		return { CreateH265MainFormat() };
	}

	std::unique_ptr<webrtc::VideoEncoder> VTVideoEncoderFactory::Create(const webrtc::Environment & env,
		const webrtc::SdpVideoFormat & format)
	{
		return std::make_unique<VTH265Encoder>(format);
	}
}
