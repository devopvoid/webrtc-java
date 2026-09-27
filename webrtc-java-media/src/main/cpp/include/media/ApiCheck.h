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

#ifndef WEBRTC_JAVA_MEDIA_API_CHECK_H_
#define WEBRTC_JAVA_MEDIA_API_CHECK_H_

#include "webrtc_java_api.h"

#include <string>

namespace ffmpeg
{
	// Checks that the function table of the loaded webrtc-java library is one
	// this module can use: the interface version it was built against, and at
	// least every member it knows of. Returns what is wrong, or an empty
	// string if nothing is.
	std::string CheckApi(const webrtc_java_api * api);
}

#endif
