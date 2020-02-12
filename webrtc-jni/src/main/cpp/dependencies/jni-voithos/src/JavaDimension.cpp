/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#include "JavaDimension.h"
#include "JavaClasses.h"
#include "JavaUtils.h"

namespace jni
{
	namespace JavaDimension
	{
		JavaLocalRef<jobject> toJava(JNIEnv * env, const int & width, const int & height)
		{
			const auto javaClass = JavaClasses::get<JavaDimensionClass>(env);

			jobject object = env->NewObject(javaClass->cls, javaClass->ctor,
				static_cast<jint>(width), static_cast<jint>(height)
			);

			return JavaLocalRef<jobject>(env, object);
		}

		JavaDimensionClass::JavaDimensionClass(JNIEnv * env)
		{
			cls = FindClass(env, "java/awt/Dimension");

			ctor = GetMethod(env, cls, "<init>", "(II)V");
		}
	}
}