/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#ifndef JNI_JAVA_WRAPPED_EXCEPTION_H_
#define JNI_JAVA_WRAPPED_EXCEPTION_H_

#include "JavaRef.h"

#ifndef _MSC_VER
#define NOEXCEPT noexcept
#else
#define NOEXCEPT
#endif

namespace jni
{
	class JavaWrappedException : public std::exception
	{
		public:
			explicit JavaWrappedException(const JavaLocalRef<jthrowable> & throwable);

			~JavaWrappedException() throw() {}

			const char * what() const NOEXCEPT;

			JavaLocalRef<jthrowable> exception() const;

		private:
			JavaLocalRef<jthrowable> throwable;
	};
}

#endif
