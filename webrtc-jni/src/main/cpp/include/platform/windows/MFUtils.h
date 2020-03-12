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

#ifndef JNI_WINDOWS_MF_UTILS_H_
#define JNI_WINDOWS_MF_UTILS_H_

#include <string>
#include <mfidl.h>

#include "common_video/libyuv/include/webrtc_libyuv.h"

namespace jni
{
	namespace mf
	{
		struct MediaFormatConfiguration {
			GUID mfMediaType;
			webrtc::VideoType videoType;
		};

		const void CreateMediaSource(GUID mediaType, std::string symlink, IMFMediaSource ** source);

		const webrtc::VideoType toVideoType(const GUID & mfMediaType);
	}
}

#endif