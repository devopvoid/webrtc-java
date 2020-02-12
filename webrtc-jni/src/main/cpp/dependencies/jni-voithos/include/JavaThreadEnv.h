/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#ifndef JNI_JAVA_THREAD_ENV_H_
#define JNI_JAVA_THREAD_ENV_H_

#include <jni.h>

namespace jni
{
	class JavaThreadEnv
	{
		public:
			explicit JavaThreadEnv(JavaVM * vm);
			~JavaThreadEnv();

			JNIEnv * getEnv() const;

		private:
			JavaVM * vm;
			JNIEnv * env;
	};
}

#endif