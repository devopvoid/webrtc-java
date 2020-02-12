/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#ifndef JNI_JAVA_ARRAY_H_
#define JNI_JAVA_ARRAY_H_

#include "Exception.h"
#include "JavaClass.h"
#include "JavaRef.h"

#include <jni.h>
#include <vector>

namespace jni
{
	namespace JavaArray
	{
		template <class T, typename Convert>
		JavaLocalRef<jobjectArray> createObjectArray(JNIEnv * env, const std::vector<T> & vector, jclass cls, Convert convert)
		{
			jsize vectorSize = static_cast<jsize>(vector.size());

			JavaLocalRef<jobjectArray> objectArray(env, env->NewObjectArray(vectorSize, cls, nullptr));

			if (objectArray.get() == nullptr) {
				throw Exception("Create object array of type [%s] failed", typeid(T).name());
			}

			for (jsize i = 0; i < vectorSize; i++) {
				const T & item = vector[i];

				JavaLocalRef<jobject> obj = static_java_ref_cast<jobject>(env, convert(env, item));

				env->SetObjectArrayElement(objectArray.get(), i, obj.get());
			}

			return objectArray;
		}

		template <class T, typename Convert>
		std::vector<T> toNativeVector(JNIEnv * env, const JavaRef<jobjectArray> & array, Convert convert)
		{
			const jsize size = static_cast<jsize>(env->GetArrayLength(array.get()));

			std::vector<T> container;
			container.reserve(size);

			for (jsize i = 0; i < size; ++i) {
				container.emplace_back(convert(env, JavaLocalRef<jobject>(env, env->GetObjectArrayElement(array.get(), i))));
			}

			return container;
		}

	}
}

#endif