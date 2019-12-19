/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#include "JavaHashMap.h"
#include "JavaClasses.h"
#include "JavaUtils.h"

namespace jni
{
	JavaHashMap::JavaHashMap(JNIEnv * env) :
		env(env),
		jMap(nullptr),
		mapClass(JavaClasses::get<JavaHashMapClass>(env))
	{
		jMap = JavaLocalRef<jobject>(env, env->NewObject(mapClass->cls, mapClass->defaultCtor));
	}

	JavaHashMap::JavaHashMap(JNIEnv * env, const JavaRef<jobject> & jMap) :
		env(env),
		jMap(env, jMap),
		mapClass(JavaClasses::get<JavaHashMapClass>(env))
	{
	}

	JavaHashMap::operator JavaLocalRef<jobject>() const
	{
		return jMap;
	}

	void JavaHashMap::put(const JavaRef<jobject> & key, const JavaRef<jobject> & value)
	{
		env->CallVoidMethod(jMap, mapClass->put, key.get(), value.get());
	}

	JavaMapIterator JavaHashMap::begin()
	{
		JavaLocalRef<jobject> entrySet = JavaLocalRef<jobject>(env, env->CallObjectMethod(jMap, mapClass->entrySet));

		return JavaMapIterator(env, entrySet);
	}

	JavaMapIterator JavaHashMap::end()
	{
		return JavaMapIterator(env);
	}

	JavaHashMap::JavaHashMapClass::JavaHashMapClass(JNIEnv * env)
	{
		cls = FindClass(env, "java/util/HashMap");

		defaultCtor = GetMethod(env, cls, "<init>", "()V");
		put = GetMethod(env, cls, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
		entrySet = GetMethod(env, cls, "entrySet", "()Ljava/util/Set;");
	}
}