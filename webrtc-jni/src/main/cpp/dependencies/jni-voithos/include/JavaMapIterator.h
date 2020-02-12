/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#ifndef JNI_JAVA_MAP_ITERATOR_H_
#define JNI_JAVA_MAP_ITERATOR_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include <jni.h>
#include <utility>

namespace jni
{
	class JavaMapIterator
	{
		public:
			/**
				Creates an iterator representing the end of any collection.
			*/
			explicit JavaMapIterator(JNIEnv * env);

			/**
				Creates an iterator pointing to the beginning of the specified collection.

				@param env The current thread's JNIEnv.
				@param iterable The Java object implementing the Iterable<T> interface.
			*/
			JavaMapIterator(JNIEnv * env, const JavaRef<jobject> & iterable);

			JavaMapIterator(JavaMapIterator && other);

			JavaMapIterator(const JavaMapIterator &) = delete;

			~JavaMapIterator() = default;

			void operator=(const JavaMapIterator &) = delete;
			JavaMapIterator & operator=(JavaMapIterator &&) = delete;

			/**
				Advances the iterator one step.

				@return itself.
			*/
			JavaMapIterator & operator++();

			bool operator==(const JavaMapIterator & other);
			bool operator!=(const JavaMapIterator & other);

			/**
				Returns the current element in the iteration.

				@return The current element.
			*/
			std::pair<JavaLocalRef<jobject>, JavaLocalRef<jobject>> & operator*();

			/**
				Tests if the iteration has more elements.

				@return true if the iteration has more elements.
			*/
			bool hasNext() const;

		private:
			class JavaMapIteratorClass : public JavaClass
			{
				public:
					explicit JavaMapIteratorClass(JNIEnv * env);

					jmethodID iterator;
					jmethodID iteratorHasNext;
					jmethodID iteratorNext;
					jmethodID getKey;
					jmethodID getValue;
			};

		private:
			JNIEnv * env;
			JavaLocalRef<jobject> iterator;
			std::pair<JavaLocalRef<jobject>, JavaLocalRef<jobject>> entry;

			const std::shared_ptr<JavaMapIteratorClass> mapIteratorClass;
	};
}

#endif