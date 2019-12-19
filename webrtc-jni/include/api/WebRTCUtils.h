/*
 * Copyright 2019 Alex Andres
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef JNI_WEBRTC_API_UTILS_H_
#define JNI_WEBRTC_API_UTILS_H_

#include "Exception.h"
#include "JavaFactories.h"

#include "api/rtc_error.h"
#include "api/scoped_refptr.h"

#include <vector>

namespace jni
{
	template <class T>
	JavaLocalRef<jobjectArray> createObjectArray(JNIEnv * env, const std::vector<rtc::scoped_refptr<T>> & vector)
	{
		jsize vectorSize = static_cast<jsize>(vector.size());

		JavaLocalRef<jobjectArray> objectArray = JavaFactories::createArray<T>(env, vectorSize);

		if (objectArray.get() == nullptr) {
			throw Exception("Create object array of type [%s] failed", typeid(T).name());
		}

		for (jsize i = 0; i < vectorSize; i++) {
			auto & item = vector[i];

			JavaLocalRef<jobject> obj = JavaFactories::create(env, item.get());

			env->SetObjectArrayElement(objectArray, i, obj.get());
		}

		return objectArray;
	}

	std::string RTCErrorToString(const webrtc::RTCError & error);
}

#endif