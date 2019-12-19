/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#ifndef JNI_JAVA_BIG_INTEGER_H_
#define JNI_JAVA_BIG_INTEGER_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include <jni.h>
#include <string>

namespace jni
{
	namespace JavaBigInteger
	{
		class JavaBigIntegerClass : public JavaClass
		{
			public:
				explicit JavaBigIntegerClass(JNIEnv * env);

				jclass cls;
				jmethodID ctor;
		};

		JavaLocalRef<jobject> toJava(JNIEnv * env, const std::string & val);
		JavaLocalRef<jobjectArray> createArray(JNIEnv * env, const std::vector<std::string> & vector);
	}
}

#endif