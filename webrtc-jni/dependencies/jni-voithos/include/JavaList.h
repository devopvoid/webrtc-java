/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#ifndef JNI_JAVA_LIST_H_
#define JNI_JAVA_LIST_H_

#include "JavaArrayList.h"
#include "JavaIterable.h"
#include "JavaRef.h"

#include <jni.h>
#include <string>
#include <vector>

namespace jni
{
	namespace JavaList
	{
		std::vector<std::string> toStringVector(JNIEnv * env, const JavaRef<jobject> & list);

		template<typename T>
		std::vector<T> toVector(JNIEnv * env, const JavaRef<jobject> & list, T conv(JNIEnv *, const JavaRef<jobject> &))
		{
			std::vector<T> vec;

			for (const auto & value : JavaIterable(env, list)) {
				vec.push_back(conv(env, value));
			}

			return vec;
		}

		template<typename T>
		JavaLocalRef<jobject> toArrayList(JNIEnv * env, const std::vector<T> & vec, JavaLocalRef<jobject> conv (JNIEnv *, const T &))
		{
			JavaArrayList list(env, vec.size());

			for (const T & value : vec) {
				list.add(conv(env, value));
			}

			return list.listObject();
		}
	}
}

#endif