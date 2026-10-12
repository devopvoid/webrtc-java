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

#include "media/video/codec/H264Format.h"

#include "api/video_codecs/h264_profile_level_id.h"
#include "modules/video_coding/codecs/h264/include/h264.h"

namespace jni
{
	std::vector<webrtc::SdpVideoFormat> CreateH264HardwareDecoderFormats()
	{
		std::vector<webrtc::SdpVideoFormat> formats = webrtc::SupportedH264DecoderCodecs();

		std::erase_if(formats, [](const webrtc::SdpVideoFormat & format) {
			const auto profileLevelId = webrtc::ParseSdpForH264ProfileLevelId(format.parameters);

			return !profileLevelId || profileLevelId->profile == webrtc::H264Profile::kProfilePredictiveHigh444;
		});

		return formats;
	}
}
