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

#include "media/ApiCheck.h"

namespace ffmpeg
{
	std::string CheckApi(const webrtc_java_api * api)
	{
		if (api == nullptr) {
			return "The webrtc-java function table is not available";
		}
		if (api->version != WEBRTC_JAVA_API_VERSION) {
			return "This module was built against webrtc-java interface version "
					+ std::to_string(WEBRTC_JAVA_API_VERSION) + ", but the loaded library provides "
					+ std::to_string(api->version);
		}
		if (api->size < sizeof(webrtc_java_api)) {
			// The same version, but from before the members this module relies
			// on were appended.
			return "The loaded webrtc-java library provides "
					+ std::to_string(api->size) + " bytes of its interface, but this module needs "
					+ std::to_string(sizeof(webrtc_java_api));
		}

		return std::string();
	}
}
