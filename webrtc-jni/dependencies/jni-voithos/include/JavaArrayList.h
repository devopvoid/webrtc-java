/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#ifndef JNI_JAVA_ARRAY_LIST_H_
#define JNI_JAVA_ARRAY_LIST_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include <jni.h>

namespace jni
{
	class JavaArrayList
	{
		public:
			explicit JavaArrayList(JNIEnv * env, size_t initialCapacity = 10);
			virtual ~JavaArrayList() = default;

			JavaArrayList(const JavaArrayList &) = delete;
			JavaArrayList(JavaArrayList && other) = delete;

			void operator=(const JavaArrayList &) = delete;
			JavaArrayList & operator=(JavaArrayList &&) = delete;

			bool add(const JavaRef<jobject> & obj);

			JavaLocalRef<jobject> get(int index);

			int size();

			JavaLocalRef<jobject> listObject();

		private:
			class JavaArrayListClass : public JavaClass
			{
				public:
					explicit JavaArrayListClass(JNIEnv * env);

					jclass cls;
					jmethodID ctor;
					jmethodID size;
					jmethodID get;
					jmethodID add;
			};

		private:
			JNIEnv * env;
			JavaLocalRef<jobject> list;

			const std::shared_ptr<JavaArrayListClass> classDef;
	};
}

#endif