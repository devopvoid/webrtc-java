/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#ifndef JNI_JAVA_RECTANGLE_H_
#define JNI_JAVA_RECTANGLE_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include <jni.h>

namespace jni
{
	class JavaRectangle : public JavaClass
	{
		public:
			explicit JavaRectangle(JNIEnv * env);

			static JavaLocalRef<jobject> toJava(JNIEnv * env, const int & x, const int & y, const int & width, const int & height);

		private:
			jclass cls;
			jmethodID ctor;
			jfieldID x;
			jfieldID y;
			jfieldID width;
			jfieldID height;
	};
}

#endif