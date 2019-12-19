/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#include "JavaMapIterator.h"
#include "JavaClasses.h"
#include "JavaUtils.h"

namespace jni
{
	JavaMapIterator::JavaMapIterator(JNIEnv * env) :
		env(nullptr),
		iterator(nullptr),
		entry(),
		mapIteratorClass(JavaClasses::get<JavaMapIteratorClass>(env))
	{
	}

	JavaMapIterator::JavaMapIterator(JNIEnv * env, const JavaRef<jobject> & iterable) :
		env(env),
		entry(std::make_pair(nullptr, nullptr)),
		mapIteratorClass(JavaClasses::get<JavaMapIteratorClass>(env))
	{
		if (iterable == nullptr) {
			return;
		}

		iterator = JavaLocalRef<jobject>(env, env->CallObjectMethod(iterable, mapIteratorClass->iterator));

		// Start at the first element.
		++(*this);
	}

	JavaMapIterator::JavaMapIterator(JavaMapIterator && other) :
		env(std::move(other.env)),
		iterator(std::move(other.iterator)),
		entry(std::move(other.entry)),
		mapIteratorClass(std::move(other.mapIteratorClass))
	{
	}

	JavaMapIterator & JavaMapIterator::operator++()
	{
		if (!hasNext()) {
			return *this;
		}

		bool hasNext = env->CallBooleanMethod(iterator, mapIteratorClass->iteratorHasNext);

		if (!hasNext) {
			iterator = nullptr;
			entry = std::make_pair(nullptr, nullptr);
			return *this;
		}

		auto mapEntry = JavaLocalRef<jobject>(env, env->CallObjectMethod(iterator, mapIteratorClass->iteratorNext));
		auto key = JavaLocalRef<jobject>(env, env->CallObjectMethod(mapEntry, mapIteratorClass->getKey));
		auto value = JavaLocalRef<jobject>(env, env->CallObjectMethod(mapEntry, mapIteratorClass->getValue));

		entry = std::make_pair(key, value);

		return *this;
	}

	bool JavaMapIterator::operator==(const JavaMapIterator & other)
	{
		return hasNext() == other.hasNext();
	}

	bool JavaMapIterator::operator!=(const JavaMapIterator & other)
	{
		return !(*this == other);
	}

	std::pair<JavaLocalRef<jobject>, JavaLocalRef<jobject>> & JavaMapIterator::operator*()
	{
		return entry;
	}

	bool JavaMapIterator::hasNext() const
	{
		return env != nullptr && iterator != nullptr;
	}

	JavaMapIterator::JavaMapIteratorClass::JavaMapIteratorClass(JNIEnv * env)
	{
		jclass setClass = FindClass(env, "java/util/Set");
		jclass iteratorClass = FindClass(env, "java/util/Iterator");
		jclass entryClass = FindClass(env, "java/util/Map$Entry");

		iterator = GetMethod(env, setClass, "iterator", "()Ljava/util/Iterator;");
		iteratorHasNext = GetMethod(env, iteratorClass, "hasNext", "()Z");
		iteratorNext = GetMethod(env, iteratorClass, "next", "()Ljava/lang/Object;");

		getKey = GetMethod(env, entryClass, "getKey", "()Ljava/lang/Object;");
		getValue = GetMethod(env, entryClass, "getValue", "()Ljava/lang/Object;");
	}
}