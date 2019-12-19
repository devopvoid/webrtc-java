/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#include "JavaList.h"
#include "JavaIterable.h"
#include "JavaString.h"

namespace jni
{
	namespace JavaList
	{
		std::vector<std::string> toStringVector(JNIEnv * env, const JavaRef<jobject> & list)
		{
			std::vector<std::string> result;

			if (list.get()) {
				for (const auto & str : JavaIterable(env, list)) {
					result.push_back(JavaString::toNative(env, static_java_ref_cast<jstring>(env, str)));
				}
			}

			return result;
		}
	}
}