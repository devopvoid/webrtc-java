/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#ifndef JNI_JAVA_CONTEXT_H_
#define JNI_JAVA_CONTEXT_H_

#include <jni.h>

namespace jni
{
	class JavaContext
	{
		public:
			explicit JavaContext(JavaVM * vm);
			virtual ~JavaContext() = default;

			JavaContext(const JavaContext &) = delete;
			JavaContext(JavaContext && other) = delete;

			void operator=(const JavaContext &) = delete;
			JavaContext & operator=(JavaContext &&) = delete;

			virtual void initialize(JNIEnv * env);
			virtual void destroy(JNIEnv * env);

			JavaVM * getVM();

		private:
			JavaVM * vm;
	};
}

extern jni::JavaContext * javaContext;

#endif