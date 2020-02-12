/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#ifndef JNI_JAVA_STRING_H_
#define JNI_JAVA_STRING_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include <jni.h>
#include <string>

namespace jni
{
	namespace JavaString
	{
		class JavaStringClass : public JavaClass
		{
			public:
				explicit JavaStringClass(JNIEnv * env);

				jclass cls;
				jmethodID getBytes;
		};

		std::string toNative(JNIEnv * env, const JavaRef<jstring> & jstr);
		JavaLocalRef<jstring> toJava(JNIEnv * env, const std::string & str);
		JavaLocalRef<jobjectArray> createArray(JNIEnv * env, const std::vector<std::string> & vector);
	}
}

#endif