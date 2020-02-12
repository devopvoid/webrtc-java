/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#include "JavaIterable.h"
#include "JavaClasses.h"
#include "JavaUtils.h"

namespace jni
{
	JavaIterator::JavaIterator(JNIEnv * env) :
		env(nullptr),
		iterator(nullptr),
		value(nullptr),
		iterableClass(JavaClasses::get<JavaIterableClass>(env))
	{
	}

	JavaIterator::JavaIterator(JNIEnv * env, const JavaRef<jobject> & iterable) :
		env(env),
		iterator(nullptr),
		value(nullptr),
		iterableClass(JavaClasses::get<JavaIterableClass>(env))
	{
		if (iterable == nullptr) {
			return;
		}

		iterator = JavaLocalRef<jobject>(env, env->CallObjectMethod(iterable, iterableClass->iterator));

		// Start at the first element.
		++(*this);
	}

	JavaIterator::JavaIterator(JavaIterator && other) :
		env(std::move(other.env)),
		iterator(std::move(other.iterator)),
		value(std::move(other.value)),
		iterableClass(std::move(other.iterableClass))
	{
	}

	JavaIterator & JavaIterator::operator++()
	{
		if (!hasNext()) {
			return *this;
		}

		bool hasNext = env->CallBooleanMethod(iterator, iterableClass->iteratorHasNext);

		if (!hasNext) {
			iterator = nullptr;
			value = nullptr;
			return *this;
		}

		value = JavaLocalRef<jobject>(env, env->CallObjectMethod(iterator, iterableClass->iteratorNext));

		return *this;
	}

	bool JavaIterator::operator==(const JavaIterator & other)
	{
		return hasNext() == other.hasNext();
	}

	bool JavaIterator::operator!=(const JavaIterator & other)
	{
		return !(*this == other);
	}

	JavaLocalRef<jobject> & JavaIterator::operator*() {
		return value;
	}

	bool JavaIterator::hasNext() const {
		return env != nullptr && iterator != nullptr;
	}

	JavaIterator::JavaIterableClass::JavaIterableClass(JNIEnv * env)
	{
		jclass iterableClass = FindClass(env, "java/lang/Iterable");
		jclass iteratorClass = FindClass(env, "java/util/Iterator");

		iterator = GetMethod(env, iterableClass, "iterator", "()Ljava/util/Iterator;");
		iteratorHasNext = GetMethod(env, iteratorClass, "hasNext", "()Z");
		iteratorNext = GetMethod(env, iteratorClass, "next", "()Ljava/lang/Object;");
	}

	

	JavaIterable::JavaIterable(JNIEnv * env, const JavaRef<jobject> & iterable) :
		env(env),
		iterable(env, iterable)
	{
	}

	JavaIterator JavaIterable::begin()
	{
		return JavaIterator(env, iterable);
	}
	
	JavaIterator JavaIterable::end()
	{
		return JavaIterator(env);
	}
}