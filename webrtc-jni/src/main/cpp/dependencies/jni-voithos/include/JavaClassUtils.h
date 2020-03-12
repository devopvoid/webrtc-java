/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#ifndef JNI_JAVA_CLASS_UTILS_H_
#define JNI_JAVA_CLASS_UTILS_H_

#include "JavaRef.h"

#include <jni.h>
#include <string>

namespace jni
{
	class JavaClassUtils : public JavaClass
	{
		public:
			explicit JavaClassUtils(JNIEnv * env);

			static std::string toNativeClassName(JNIEnv * env, const JavaLocalRef<jobject> & javaRef);

		private:
			jmethodID getClassName;
	};
}

#endif