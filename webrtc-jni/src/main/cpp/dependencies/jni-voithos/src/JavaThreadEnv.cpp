/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#include "JavaThreadEnv.h"

#include <iostream>
#include <thread>

namespace jni
{
	JavaThreadEnv::JavaThreadEnv(JavaVM * vm) :
		vm(vm),
		env(nullptr)
	{
		int status = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);

		if (status == JNI_EDETACHED) {
			if (vm->AttachCurrentThread(reinterpret_cast<void**>(&env), NULL) != 0) {
				std::cout << "VM attach current thread failed" << std::endl;
			}
		}

		if (env == nullptr) {
			std::cout << "Failed to attach thread " << std::this_thread::get_id() << std::endl;
		}
	}

	JavaThreadEnv::~JavaThreadEnv()
	{
		vm->DetachCurrentThread();

		//std::cout << "Dettached thread " << std::this_thread::get_id() << std::endl;
	}

	JNIEnv * JavaThreadEnv::getEnv() const
	{
		return env;
	}
}