/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#ifndef JNI_WINDOWS_WIN_UTILS_H_
#define JNI_WINDOWS_WIN_UTILS_H_

#include "Exception.h"

#include <comdef.h>
#include <string>
#include <Windows.h>


#define THROW_IF_FAILED(hr, msg, ...) ThrowIfFailed(hr, msg, __VA_ARGS__)


inline std::string WideStrToStr(LPCWSTR wstr)
{
	int wslen = static_cast<int>(wcslen(wstr));
	int length = WideCharToMultiByte(CP_UTF8, 0, wstr, wslen, NULL, 0, NULL, NULL);
	std::string str(length, 0);
	WideCharToMultiByte(CP_UTF8, 0, wstr, wslen, &str[0], length, NULL, NULL);

	return str;
}

inline void ThrowIfFailed(HRESULT hr, const char * msg, ...) {
	if (FAILED(hr)) {
		char message[256];

		va_list args;
		va_start(args, msg);
		vsnprintf(message, 256, msg, args);
		va_end(args);

		std::string comMessage = _com_error(hr).ErrorMessage();

		throw jni::Exception("%s %s", message, comMessage.c_str());
	}
}

#endif