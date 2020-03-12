/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#ifndef JNI_JAVA_DIMENSION_H_
#define JNI_JAVA_DIMENSION_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include <jni.h>

namespace jni
{
	class JavaDimension : public JavaClass
	{
		public:
			explicit JavaDimension(JNIEnv * env);

			static JavaLocalRef<jobject> toJava(JNIEnv * env, const int & width, const int & height);

		private:
			jclass cls;
			jmethodID ctor;
			jfieldID width;
			jfieldID height;
	};
}

#endif