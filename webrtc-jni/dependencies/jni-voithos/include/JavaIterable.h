/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#ifndef JNI_JAVA_ITERABLE_H_
#define JNI_JAVA_ITERABLE_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include <jni.h>

namespace jni
{
	class JavaIterator
	{
		public:
			/**
				Creates an iterator representing the end of any collection.
			*/
			explicit JavaIterator(JNIEnv * env);

			/**
				Creates an iterator pointing to the beginning of the specified collection.

				@param env The current thread's JNIEnv.
				@param iterable The Java object implementing the Iterable<T> interface.
			*/
			JavaIterator(JNIEnv * env, const JavaRef<jobject> & iterable);

			JavaIterator(JavaIterator && other);
			JavaIterator(const JavaIterator &) = delete;
			
			~JavaIterator() = default;

			void operator=(const JavaIterator &) = delete;
			JavaIterator & operator=(JavaIterator &&) = delete;
 
			/**
				Advances the iterator one step.

				@return itself.
			*/
			JavaIterator & operator++();

			bool operator==(const JavaIterator & other);
			bool operator!=(const JavaIterator & other);

			/**
				Returns the current element in the iteration.

				@return The current element.
			*/
			JavaLocalRef<jobject> & operator*();

			/**
				Tests if the iteration has more elements.

				@return true if the iteration has more elements.
			*/
			bool hasNext() const;

		private:
			class JavaIterableClass : public JavaClass
			{
				public:
					explicit JavaIterableClass(JNIEnv * env);
					
					jmethodID iterator;
					jmethodID iteratorHasNext;
					jmethodID iteratorNext;
			};

		private:
			JNIEnv * env;
			JavaLocalRef<jobject> iterator;
			JavaLocalRef<jobject> value;

			const std::shared_ptr<JavaIterableClass> iterableClass;
	};

	class JavaIterable
	{
		public:
			/**
				Creates an Iterable wrapper that can be used with the range for loop.

				@param env The current thread's JNIEnv.
				@param iterable The Java object implementing the Iterable<T> interface.
			*/
			JavaIterable(JNIEnv * env, const JavaRef<jobject> & iterable);
			JavaIterable(JavaIterable && other) = default;
			JavaIterable(const JavaIterable &) = delete;
			~JavaIterable() = default;

			void operator=(const JavaIterable &) = delete;

			/**
				Creates an iterator pointing to the beginning of the collection.

				@return An iterator pointing to the first element.
			*/
			JavaIterator begin();

			/**
				Creates an iterator pointing to the end of the collection.

				@return An iterator pointing to the past-the-end element.
			*/
			JavaIterator end();

		private:
			JNIEnv * env;
			JavaLocalRef<jobject> iterable;
	};
}

#endif