/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#ifndef JNI_JAVA_HASH_MAP_H_
#define JNI_JAVA_HASH_MAP_H_

#include "JavaClass.h"
#include "JavaRef.h"
#include "JavaMapIterator.h"

#include <jni.h>

namespace jni
{
	class JavaHashMap
	{
		public:
			explicit JavaHashMap(JNIEnv * env);
			JavaHashMap(JNIEnv * env, const JavaRef<jobject> & jMap);
			~JavaHashMap() = default;

			void operator=(const JavaHashMap &) = delete;

			operator JavaLocalRef<jobject>() const;

			void put(const JavaRef<jobject> & key, const JavaRef<jobject> & value);

			/**
				Creates an iterator pointing to the beginning of the collection.

				@return An iterator pointing to the first element.
			*/
			JavaMapIterator begin();

			/**
				Creates an iterator pointing to the end of the collection.

				@return An iterator pointing to the past-the-end element.
			*/
			JavaMapIterator end();

		private:
			class JavaHashMapClass : public JavaClass
			{
				public:
					explicit JavaHashMapClass(JNIEnv * env);

					jclass cls;
					jmethodID defaultCtor;
					jmethodID put;
					jmethodID entrySet;
			};

		private:
			JNIEnv * env;
			JavaLocalRef<jobject> jMap;
			
			const std::shared_ptr<JavaHashMapClass> mapClass;
	};
	
}

#endif