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
#include "media/video/codec/windows/MFVideoEncoder.h"
#include "platform/windows/ComInitializer.h"
#include "platform/windows/MFInitializer.h"

#include "api/video_codecs/h264_profile_level_id.h"
#include "modules/video_coding/codecs/h264/include/h264.h"
#include "api/video/video_codec_type.h"
#include "api/video_codecs/video_codec.h"
#include "rtc_base/logging.h"

using Microsoft::WRL::ComPtr;

namespace jni
{
	namespace
	{
		bool HasHardwareEncoder(const GUID & format)
		{
			std::vector<ComPtr<IMFActivate>> encoders;

			if (FAILED(EnumerateHardwareEncoders(format, encoders)) || encoders.empty()) {
				return false;
			}

			RTC_LOG(LS_INFO) << "Media Foundation hardware encoder: " << GetTransformName(encoders.front().Get());

			return true;
		}
	}

	std::unique_ptr<MFVideoEncoderFactory> MFVideoEncoderFactory::Create()
	{
		bool h264 = false;
		bool av1 = false;

		try {
			ComInitializer comInitializer;
			MFInitializer mfInitializer;

			h264 = HasHardwareEncoder(MFVideoFormat_H264);
			av1 = HasHardwareEncoder(MFVideoFormat_AV1);
		}
		catch (...) {
			return nullptr;
		}

		if (!h264 && !av1) {
			RTC_LOG(LS_INFO) << "No Media Foundation hardware encoder found";
			return nullptr;
		}

		return std::unique_ptr<MFVideoEncoderFactory>(new MFVideoEncoderFactory(h264, av1));
	}

	MFVideoEncoderFactory::MFVideoEncoderFactory(bool h264, bool av1) :
		h264(h264),
		av1(av1)
	{
	}

	std::vector<webrtc::SdpVideoFormat> MFVideoEncoderFactory::GetSupportedFormats() const
	{
		std::vector<webrtc::SdpVideoFormat> formats;

		if (h264) {
			formats.push_back(webrtc::CreateH264Format(webrtc::H264Profile::kProfileConstrainedBaseline,
				webrtc::H264Level::kLevel3_1, "1"));
			formats.push_back(webrtc::CreateH264Format(webrtc::H264Profile::kProfileBaseline,
				webrtc::H264Level::kLevel3_1, "1"));
		}
		if (av1) {
			formats.push_back(webrtc::SdpVideoFormat::AV1Profile0());
		}

		return formats;
	}

	std::unique_ptr<webrtc::VideoEncoder> MFVideoEncoderFactory::Create(const webrtc::Environment & env,
		const webrtc::SdpVideoFormat & format)
	{
		const webrtc::VideoCodecType codec = webrtc::PayloadStringToCodecType(format.name);

		return std::make_unique<MFVideoEncoder>(codec, format);
	}
}
