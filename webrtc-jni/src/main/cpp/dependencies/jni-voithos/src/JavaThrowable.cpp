/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#include "JavaThrowable.h"
#include "JavaUtils.h"

#include <stdarg.h>

namespace jni
{
	JavaThrowable::JavaThrowable(JNIEnv * env, const char * message, ...) :
		env(env)
	{
		if (message != nullptr) {
			char buffer[MAX_MESSAGE_LEN];

			va_list args;
			va_start(args, message);
			vsnprintf(buffer, MAX_MESSAGE_LEN, message, args);
			va_end(args);

			this->message = buffer;
		}
	}

	JavaThrowable::operator jthrowable() const
	{
		return createThrowable<JavaThrowableClass>();
	}
	
	JavaThrowable::JavaThrowableClass::JavaThrowableClass(JNIEnv * env) :
		JavaThrowableClass(env, "java/lang/Throwable")
	{
	}

	JavaThrowable::JavaThrowableClass::JavaThrowableClass(JNIEnv * env, const char * className)
	{
		cls = FindClass(env, className);

		ctor = GetMethod(env, cls, "<init>", "(Ljava/lang/String;)V");
	}
}