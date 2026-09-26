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

#include "media/ErrorText.h"

#include <cerrno>

extern "C" {
#include <libavutil/error.h>
}

namespace
{
	struct NamedError
	{
		int error;
		const char * text;
	};

	// Spelled out rather than left to av_strerror, which hands plain errno
	// codes to the C runtime: its words for these differ between platforms,
	// and MSVC's has none at all for the networking ones.
	const NamedError kNamedErrors[] = {
		{ AVERROR(ETIMEDOUT), "Timed out" },
		{ AVERROR(ECONNREFUSED), "Connection refused" },
		{ AVERROR(ECONNRESET), "Connection reset" },
		{ AVERROR(ECONNABORTED), "Connection aborted" },
		{ AVERROR(EHOSTUNREACH), "Host unreachable" },
		{ AVERROR(ENETUNREACH), "Network unreachable" },
#ifdef _WIN32
		// FFmpeg maps only a few Winsock errors to errno codes, and hands
		// the others on as the negated Winsock code. These are the ones a
		// dropped or unreachable stream produces.
		{ -10050, "Network is down" },			// WSAENETDOWN
		{ -10051, "Network unreachable" },		// WSAENETUNREACH
		{ -10053, "Connection aborted" },		// WSAECONNABORTED
		{ -10054, "Connection reset" },			// WSAECONNRESET
		{ -10065, "Host unreachable" },			// WSAEHOSTUNREACH
#endif
	};
}

namespace ffmpeg
{
	std::string ErrorText(int error)
	{
		for (const NamedError & named : kNamedErrors) {
			if (named.error == error) {
				return named.text;
			}
		}

		char buffer[AV_ERROR_MAX_STRING_SIZE] = { 0 };

		if (av_strerror(error, buffer, sizeof(buffer)) < 0) {
			return "FFmpeg error " + std::to_string(error);
		}

		return buffer;
	}
}
