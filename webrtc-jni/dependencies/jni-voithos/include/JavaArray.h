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

				JavaLocalRef<jobject> obj = jni::static_java_ref_cast<jobject>(env, convert(env, item));

				env->SetObjectArrayElement(objectArray.get(), i, obj.get());
			}

			return objectArray;
		}
	}
}

#endif