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
		const bool added = env->CallBooleanMethod(list, classDef->add, obj.get());
		ExceptionCheck(env);

		return added;
	}

	JavaLocalRef<jobject> JavaArrayList::get(int index)
	{
		JavaLocalRef<jobject> element(env, env->CallObjectMethod(list, classDef->get, index));
		ExceptionCheck(env);

		return element;
	}

	int JavaArrayList::size()
	{
		const int size = env->CallIntMethod(list, classDef->size);
		ExceptionCheck(env);

		return size;
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