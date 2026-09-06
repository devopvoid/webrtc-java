/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#include "JavaThreadEnv.h"

namespace jni
{
	JavaThreadEnv::JavaThreadEnv(JavaVM * vm) :
		vm(vm),
		env(nullptr),
		attached(false)
	{
		int status = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);

		if (status == JNI_EDETACHED) {
			if (vm->AttachCurrentThread(reinterpret_cast<void**>(&env), NULL) == JNI_OK) {
				attached = true;
			}
			else {
				env = nullptr;
			}
		}
	}

	JavaThreadEnv::~JavaThreadEnv()
	{
		if (attached) {
			vm->DetachCurrentThread();
		}
	}

	JNIEnv * JavaThreadEnv::getEnv() const
	{
		return env;
	}
}