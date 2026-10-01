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

#include "media/video/codec/macos/VTVideoDecoderFactory.h"
#include "media/video/codec/macos/VTVp9Decoder.h"

#include "rtc_base/logging.h"

#include <VideoToolbox/VideoToolbox.h>

namespace jni
{
	namespace
	{
		// Whether this Mac decodes VP9 in hardware. VideoToolbox has the VP9
		// decoder only after it is registered, so that comes first. The
		// answer does not change while the process runs, and is asked once.
		bool HasHardwareVp9Decoder()
		{
			static const bool available = [] {
				VTRegisterSupplementalVideoDecoderIfAvailable(kCMVideoCodecType_VP9);

				const bool supported = VTIsHardwareDecodeSupported(kCMVideoCodecType_VP9);

				RTC_LOG(LS_INFO) << "VideoToolbox hardware decoder for VP9: " << supported;

				return supported;
			}();

			return available;
		}
	}

	std::unique_ptr<VTVideoDecoderFactory> VTVideoDecoderFactory::Create()
	{
		if (!HasHardwareVp9Decoder()) {
			return nullptr;
		}

		return std::unique_ptr<VTVideoDecoderFactory>(new VTVideoDecoderFactory());
	}

	std::vector<webrtc::SdpVideoFormat> VTVideoDecoderFactory::GetSupportedFormats() const
	{
		return { webrtc::SdpVideoFormat::VP9Profile0() };
	}

	std::unique_ptr<webrtc::VideoDecoder> VTVideoDecoderFactory::Create(const webrtc::Environment & env,
		const webrtc::SdpVideoFormat & format)
	{
		return std::make_unique<VTVp9Decoder>();
	}
}
