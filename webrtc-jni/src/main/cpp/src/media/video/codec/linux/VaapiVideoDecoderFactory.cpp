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

#include "media/video/codec/linux/VaapiVideoDecoderFactory.h"
#include "media/video/codec/linux/VaapiVp9Decoder.h"

#include "rtc_base/logging.h"

namespace jni
{
	std::unique_ptr<VaapiVideoDecoderFactory> VaapiVideoDecoderFactory::Create()
	{
		VaapiLibrary * library = VaapiLibrary::Get();

		if (library == nullptr) {
			return nullptr;
		}

		const bool vp9 = library->Decodes(VAProfileVP9Profile0);

		RTC_LOG(LS_INFO) << "VA-API (" << library->Vendor() << ") VP9 decoding: " << vp9;

		if (!vp9) {
			return nullptr;
		}

		return std::unique_ptr<VaapiVideoDecoderFactory>(new VaapiVideoDecoderFactory(*library));
	}

	VaapiVideoDecoderFactory::VaapiVideoDecoderFactory(VaapiLibrary & library) :
		library(library)
	{
	}

	std::vector<webrtc::SdpVideoFormat> VaapiVideoDecoderFactory::GetSupportedFormats() const
	{
		return { webrtc::SdpVideoFormat::VP9Profile0() };
	}

	std::unique_ptr<webrtc::VideoDecoder> VaapiVideoDecoderFactory::Create(const webrtc::Environment & env,
		const webrtc::SdpVideoFormat & format)
	{
		return std::make_unique<VaapiVp9Decoder>(library);
	}
}
