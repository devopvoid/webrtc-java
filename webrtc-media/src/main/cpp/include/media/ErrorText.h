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

#ifndef WEBRTC_JAVA_MEDIA_ERROR_TEXT_H_
#define WEBRTC_JAVA_MEDIA_ERROR_TEXT_H_

#include <string>

namespace ffmpeg
{
	// What a negative AVERROR code means, in words, for messages that reach
	// Java. FFmpeg describes most codes itself, but leaves plain errno codes
	// to the C runtime, and on Windows that has no words for some of the
	// ones networking produces, such as a timeout.
	std::string ErrorText(int error);
}

#endif
