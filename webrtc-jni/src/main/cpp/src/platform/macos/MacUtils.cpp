/*
 * Copyright 2022 Alex Andres
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

#include "platform/macos/MacUtils.h"

#include <vector>

namespace jni
{
	const std::string CFStringRefToUTF8(CFStringRef stringRef) {
		CFIndex length = CFStringGetLength(stringRef);

		if (length == 0) {
			return std::string();
		}

		CFRange range = CFRangeMake(0, length);
		CFIndex outputSize;
		CFIndex converted = CFStringGetBytes(stringRef, range, kCFStringEncodingUTF8, 0, false, nullptr, 0, &outputSize);

		if (converted == 0 || outputSize <= 0) {
			return std::string();
		}

		size_t elements = static_cast<size_t>(outputSize) * sizeof(UInt8) / sizeof(char) + 1;

		std::vector<char> buffer(elements);

		converted = CFStringGetBytes(stringRef, range, kCFStringEncodingUTF8, 0, false,
			reinterpret_cast<UInt8*>(&buffer[0]), outputSize, nullptr);

		if (converted == 0) {
			return std::string();
		}

		// Terminate string.
		buffer[elements - 1] = '\0';

		return std::string(&buffer[0], elements - 1);
	}
}