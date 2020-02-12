/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#include "JavaArrayList.h"
#include "JavaClasses.h"
#include "JavaUtils.h"

namespace jni
{
	JavaArrayList::JavaArrayList(JNIEnv * env, size_t initialCapacity) :
		env(env),
		classDef(JavaClasses::get<JavaArrayListClass>(env))
	{
		list = JavaLocalRef<jobject>(env, env->NewObject(classDef->cls, classDef->ctor, initialCapacity));
	}

	bool JavaArrayList::add(const JavaRef<jobject> & obj)
	{
		return env->CallBooleanMethod(list, classDef->add, obj.get());
	}

	JavaLocalRef<jobject> JavaArrayList::get(int index)
	{
		return JavaLocalRef<jobject>(env, env->CallObjectMethod(list, classDef->get, index));
	}

	int JavaArrayList::size()
	{
		return env->CallIntMethod(list, classDef->size);
	}

	JavaLocalRef<jobject> JavaArrayList::listObject()
	{
		return list;
	}

	JavaArrayList::JavaArrayListClass::JavaArrayListClass(JNIEnv * env)
	{
		cls = FindClass(env, "java/util/ArrayList");

		ctor = GetMethod(env, cls, "<init>", "(I)V");
		add = GetMethod(env, cls, "add", "(Ljava/lang/Object;)Z");
		get = GetMethod(env, cls, "get", "(I)Ljava/lang/Object;");
		size = GetMethod(env, cls, "size", "()I");
	}
}