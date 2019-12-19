/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#include "Exception.h"

namespace jni
{
	Exception::Exception() : std::exception()
	{
	}

	Exception::Exception(const char * msg, ...) : std::exception()
	{
		char buffer[MAX_MESSAGE_LEN];

		va_list args;
		va_start(args, msg);
		vsnprintf(buffer, MAX_MESSAGE_LEN, msg, args);
		va_end(args);

		message = buffer;
	}

	const char * Exception::what() const NOEXCEPT
	{
		return message.c_str();
	}
}
