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

#ifndef JNI_MACOS_MAC_UTILS_H_
#define JNI_MACOS_MAC_UTILS_H_

#include "Exception.h"

#include <string>

#import <Foundation/Foundation.h>


inline void ThrowIfFailed(OSStatus status, const char * msg, ...)
{
	if (status != noErr) {
		char message[256];

		va_list args;
		va_start(args, msg);
		vsnprintf(message, 256, msg, args);
		va_end(args);

		throw jni::Exception("%s Status: %d", message, status);
	}
}

namespace jni
{
	const std::string CFStringRefToUTF8(CFStringRef stringRef);
}

#endif