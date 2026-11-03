/*
 * Copyright (c) 2026, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#ifndef JNI_JAVA_BOUNDARY_H_
#define JNI_JAVA_BOUNDARY_H_

#include "JavaUtils.h"

#include <jni.h>

namespace jni
{
	namespace detail
	{
		// What a native method returns when its body failed: null, zero or
		// false, which Java never sees, since an exception is pending then.
		template <typename R>
		struct FailedReturn
		{
			static R value()
			{
				return R();
			}
		};

		template <>
		struct FailedReturn<void>
		{
			static void value()
			{
			}
		};
	}

	// Runs the body of a JNI entry point. A C++ exception that reaches the VM
	// ends the process, and the helpers of this library throw them: a Java
	// exception they ran into as JavaWrappedException, a failed allocation as
	// std::bad_alloc. Here every C++ exception becomes a Java one, with
	// ThrowCxxJavaException(), and the entry point returns null, zero or
	// false. What the body holds is released before that, so a destructor may
	// still call into Java. The body's own returns, also those of
	// CHECK_HANDLE, return from the entry point as before:
	//
	//   JNIEXPORT jobject JNICALL Java_..._getFoo(JNIEnv * env, jobject caller)
	//   {
	//       return jni::CallFromJava(env, [&]() -> jobject {
	//           ...
	//       });
	//   }
	//
	// A body that catches its own exceptions, such as one with a try block of
	// its own around everything that may throw, needs none of this.
	template <typename Body>
	auto CallFromJava(JNIEnv * env, Body && body) noexcept -> decltype(body())
	{
		try {
			return body();
		}
		catch (...) {
			ThrowCxxJavaException(env);
		}

		return detail::FailedReturn<decltype(body())>::value();
	}
}

#endif
