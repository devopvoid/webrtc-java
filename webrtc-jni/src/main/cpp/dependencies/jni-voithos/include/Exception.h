/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#ifndef JNI_EXCEPTION_H_
#define JNI_EXCEPTION_H_

#include <stdarg.h>
#include <string>

#ifndef _MSC_VER
#define NOEXCEPT noexcept
#else
#define NOEXCEPT
#endif

namespace jni
{
	class Exception : public std::exception
	{
		public:
			Exception();
			Exception(const char * msg, ...);

			virtual ~Exception() throw() {}

			virtual const char * what() const NOEXCEPT;

		protected:
			std::string message;

			static const uint16_t MAX_MESSAGE_LEN = 1024;
	};
}

#endif
