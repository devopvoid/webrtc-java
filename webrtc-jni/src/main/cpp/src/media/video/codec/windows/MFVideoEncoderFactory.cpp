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

#include "media/video/codec/windows/MFVideoEncoderFactory.h"
#include "media/video/codec/windows/MFEncoderUtils.h"
#include "media/video/codec/windows/MFH264Encoder.h"
#include "platform/windows/ComInitializer.h"
#include "platform/windows/MFInitializer.h"

#include "api/video_codecs/h264_profile_level_id.h"
#include "modules/video_coding/codecs/h264/include/h264.h"
#include "rtc_base/logging.h"

using Microsoft::WRL::ComPtr;

namespace jni
{
	std::unique_ptr<MFVideoEncoderFactory> MFVideoEncoderFactory::Create()
	{
		std::vector<ComPtr<IMFActivate>> encoders;

		try {
			ComInitializer comInitializer;
			MFInitializer mfInitializer;

			if (FAILED(EnumerateHardwareH264Encoders(encoders))) {
				return nullptr;
			}
		}
		catch (...) {
			return nullptr;
		}

		if (encoders.empty()) {
			RTC_LOG(LS_INFO) << "No Media Foundation hardware H.264 encoder found";

			return nullptr;
		}

		RTC_LOG(LS_INFO) << "Media Foundation hardware H.264 encoder: " << GetTransformName(encoders.front().Get());

		return std::unique_ptr<MFVideoEncoderFactory>(new MFVideoEncoderFactory());
	}

	std::vector<webrtc::SdpVideoFormat> MFVideoEncoderFactory::GetSupportedFormats() const
	{
		return {
			webrtc::CreateH264Format(webrtc::H264Profile::kProfileConstrainedBaseline, webrtc::H264Level::kLevel3_1, "1"),
			webrtc::CreateH264Format(webrtc::H264Profile::kProfileBaseline, webrtc::H264Level::kLevel3_1, "1")
		};
	}

	std::unique_ptr<webrtc::VideoEncoder> MFVideoEncoderFactory::Create(const webrtc::Environment & env,
		const webrtc::SdpVideoFormat & format)
	{
		return std::make_unique<MFH264Encoder>(format);
	}
}
