/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#ifndef JNI_JAVA_CONTEXT_H_
#define JNI_JAVA_CONTEXT_H_

#include "JavaClassUtils.h"
#include "JavaRef.h"
#include "JavaString.h"

#include <jni.h>
#include <list>
#include <memory>
#include <string>
#include <unordered_map>
#include <utility>

namespace jni
{
	class JavaContext
	{
		public:
			explicit JavaContext(JavaVM * vm);
			virtual ~JavaContext() = default;

			JavaContext(const JavaContext &) = delete;
			JavaContext(JavaContext && other) = delete;

			void operator=(const JavaContext &) = delete;
			JavaContext & operator=(JavaContext &&) = delete;

			virtual void initialize(JNIEnv * env) = 0;
			virtual void initializeClassLoader(JNIEnv * env, const char * loaderName) = 0;
			virtual void destroy(JNIEnv * env) = 0;

			JavaVM * getVM();

			void addNativeRef(JNIEnv * env, const JavaLocalRef<jobject> & javaRef, const std::shared_ptr<void> & nativeRef);

			template<typename T>
			std::shared_ptr<T> removeNativeRef(JNIEnv * env, const JavaLocalRef<jobject> & javaRef)
			{
				auto className = JavaClassUtils::toNativeClassName(env, javaRef);
				auto it = objectMap.find(className);

				if (it == objectMap.end()) {
					return nullptr;
				}

				auto globalRef = JavaGlobalRef<jobject>(env, javaRef.get());
				auto & list = it->second;
				std::shared_ptr<T> nativeRef = nullptr;

				for (auto it = list.begin(); it != list.end(); ++it) {
					if (env->IsSameObject(it->first, globalRef)) {
						nativeRef = std::static_pointer_cast<T>(it->second);
						list.erase(it);
						break;
					}
				}

				env->DeleteGlobalRef(globalRef);

				return nativeRef;
			}

		private:
			JavaVM * vm;
			// Java object class name mapped to a Java object reference and its native object reference.
			std::unordered_map<std::string, std::list<std::pair<JavaGlobalRef<jobject>, std::shared_ptr<void>>>> objectMap;
	};
}

extern jni::JavaContext * javaContext;

#endif