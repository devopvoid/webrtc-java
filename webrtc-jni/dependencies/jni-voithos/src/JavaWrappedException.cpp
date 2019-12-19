/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#include "JavaWrappedException.h"

namespace jni
{
	JavaWrappedException::JavaWrappedException(const JavaLocalRef<jthrowable> & throwable) :
		std::exception(),
		throwable(throwable)
	{
	}

	const char * JavaWrappedException::what() const NOEXCEPT
	{
		return "";
	}

	JavaLocalRef<jthrowable> JavaWrappedException::exception() const
	{
		return throwable;
	}
}
