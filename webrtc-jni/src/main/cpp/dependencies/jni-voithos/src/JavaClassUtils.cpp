/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#include "JavaClasses.h"
#include "JavaClassUtils.h"
#include "JavaString.h"

namespace jni
{
	JavaClassUtils::JavaClassUtils(JNIEnv * env)
	{
		JavaLocalRef<jclass> cls(env, env->FindClass("java/lang/Class"));

		getClassName = GetMethod(env, cls.get(), "getName", "()Ljava/lang/String;");
	}

	std::string JavaClassUtils::toNativeClassName(JNIEnv * env, const JavaLocalRef<jobject> & javaRef)
	{
		const auto classUtils = JavaClasses::get<JavaClassUtils>(env);

		JavaLocalRef<jclass> cls(env, env->GetObjectClass(javaRef.get()));
		jstring clsName = static_cast<jstring>(env->CallObjectMethod(cls.get(), classUtils->getClassName));
		ExceptionCheck(env);

		return JavaString::toNative(env, JavaLocalRef<jstring>(env, clsName));
	}
}