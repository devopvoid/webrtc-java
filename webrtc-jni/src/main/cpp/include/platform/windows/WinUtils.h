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

#ifndef JNI_WINDOWS_WIN_UTILS_H_
#define JNI_WINDOWS_WIN_UTILS_H_

#include "Exception.h"

#include <comdef.h>
#include "mmdeviceapi.h"
#include <string>
#include <stdexcept>
#include <windows.h>


#define THROW_IF_FAILED(hr, msg, ...) ThrowIfFailed(hr, msg, __VA_ARGS__)


inline std::string WideStrToStr(LPCWSTR wstr)
{
	int wslen = static_cast<int>(wcslen(wstr));
	int length = WideCharToMultiByte(CP_UTF8, 0, wstr, wslen, NULL, 0, NULL, NULL);
	std::string str(length, 0);
	WideCharToMultiByte(CP_UTF8, 0, wstr, wslen, &str[0], length, NULL, NULL);

	return str;
}

inline std::wstring UTF8Decode(const std::string & str)
{
	if (str.empty()) {
		return std::wstring();
	}

	int strSize = MultiByteToWideChar(CP_UTF8, 0, &str[0], (int) str.size(), NULL, 0);
	if (strSize <= 0) {
		throw std::runtime_error("MultiByteToWideChar() failed: " + std::to_string(strSize));
	}

	std::wstring wstrTo(strSize, 0);
	MultiByteToWideChar(CP_UTF8, 0, &str[0], (int) str.size(), &wstrTo[0], strSize);

	return wstrTo;
}

inline std::string UTF8Encode(const std::wstring & wstr)
{
	if (wstr.empty()) {
		return std::string();
	}

	int strSize = WideCharToMultiByte(CP_UTF8, 0, &wstr[0], (int) wstr.size(), NULL, 0, NULL, NULL);
	if (strSize <= 0) {
		throw std::runtime_error("WideCharToMultiByte() failed: " + std::to_string(strSize));
	}

	std::string str(strSize, 0);
	WideCharToMultiByte(CP_UTF8, 0, &wstr[0], (int) wstr.size(), &str[0], strSize, NULL, NULL);

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

inline std::string RoleToStr(ERole role)
{
	switch (role) {
		case eConsole:
			return "Console";
		case eMultimedia:
			return "Multimedia";
		case eCommunications:
			return "Communications";
		default:
			return "Unknown Role";
	}
}

#endif