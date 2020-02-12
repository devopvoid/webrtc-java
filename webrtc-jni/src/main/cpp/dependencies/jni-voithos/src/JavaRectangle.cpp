/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#include "JavaRectangle.h"
#include "JavaClasses.h"
#include "JavaUtils.h"

namespace jni
{
	namespace JavaRectangle
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const int & x, const int & y, const int & width, const int & height)
		{
			const auto javaClass = JavaClasses::get<JavaRectangleClass>(env);

			jobject object = env->NewObject(javaClass->cls, javaClass->ctor,
				static_cast<jint>(x), static_cast<jint>(y),
				static_cast<jint>(width), static_cast<jint>(height)
			);

			return JavaLocalRef<jobject>(env, object);
		}

		JavaRectangleClass::JavaRectangleClass(JNIEnv * env)
		{
			cls = FindClass(env, "java/awt/Rectangle");

			ctor = GetMethod(env, cls, "<init>", "(IIII)V");
		}
	}
}