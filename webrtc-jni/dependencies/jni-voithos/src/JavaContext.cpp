/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#include "JavaContext.h"

namespace jni
{
	JavaContext::JavaContext(JavaVM * vm) :
		vm(vm)
	{
	}

	void JavaContext::initialize(JNIEnv * env)
	{
	}

	void JavaContext::destroy(JNIEnv * env)
	{
	}

	JavaVM * JavaContext::getVM()
	{
		return vm;
	}
}