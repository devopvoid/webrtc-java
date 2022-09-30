/*
 * Copyright 2019 Alex Andres
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

#include "platform/windows/MFUtils.h"
#include "platform/windows/WinUtils.h"
#include "platform/windows/ComPtr.h"

#include <locale>
#include <unordered_map>
#include <mfapi.h>
#include <VersionHelpers.h>

namespace jni
{
	namespace mf
	{
		const void CreateMediaSource(GUID mediaType, std::string symlink, IMFMediaSource ** source)
		{
			ComPtr<IMFMediaSource> mediaSource;
			ComPtr<IMFAttributes> attributes;
			GUID sourceType;
			GUID idKey;
			HRESULT hr;

			std::wstring link = UTF8Decode(symlink);

			hr = MFCreateAttributes(&attributes, 2);
			THROW_IF_FAILED(hr, "MMF: Create attributes failed");

			if (mediaType == MFMediaType_Audio) {
				sourceType = MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_AUDCAP_GUID;
				idKey = MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_AUDCAP_ENDPOINT_ID;
			}
			else if (mediaType == MFMediaType_Video) {
				sourceType = MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID;
				idKey = MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_SYMBOLIC_LINK;
			}
			else {
				throw Exception("MMF: Media type is not supported");
			}

			hr = attributes->SetGUID(MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE, sourceType);
			THROW_IF_FAILED(hr, "MMF: Set source type failed");

			hr = attributes->SetString(idKey, link.c_str());
			THROW_IF_FAILED(hr, "MMF: Set source ID key failed");

			hr = MFCreateDeviceSource(attributes, &mediaSource);
			THROW_IF_FAILED(hr, "MMF: Create video source failed");

			*source = mediaSource;
			(*source)->AddRef();
		}

		const webrtc::VideoType toVideoType(const GUID & mfMediaType)
		{
			static const MediaFormatConfiguration mediaTypeMap[] = {
				{MFVideoFormat_I420, webrtc::VideoType::kI420},
				{MFVideoFormat_YUY2, webrtc::VideoType::kYUY2},
				{MFVideoFormat_UYVY, webrtc::VideoType::kUYVY},
				{MFVideoFormat_IYUV, webrtc::VideoType::kIYUV},
				{MFVideoFormat_RGB24, webrtc::VideoType::kRGB24},
				{MFVideoFormat_RGB32, webrtc::VideoType::kBGRA},
				{MFVideoFormat_ARGB32, webrtc::VideoType::kARGB},
				{MFVideoFormat_MJPG, webrtc::VideoType::kMJPEG},
				{MFVideoFormat_YV12, webrtc::VideoType::kYV12}
			};

			for (const auto & mediaMapping : mediaTypeMap) {
				if (mediaMapping.mfMediaType == mfMediaType) {
					return mediaMapping.videoType;
				}
			}

			return webrtc::VideoType::kUnknown;
		}
	}
}