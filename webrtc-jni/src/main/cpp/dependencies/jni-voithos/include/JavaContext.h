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
#include <mutex>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

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

			// Keeps a native object (a listener's native peer) alive for as long as
			// the Java object stays registered. An entry is found again by the Java
			// object together with its owner and kind: the same Java listener can be
			// registered with several owners (tracks), or as several kinds of
			// listener with one owner, and each registration has its own native
			// object.
			void addNativeRef(JNIEnv * env, const JavaLocalRef<jobject> & javaRef, const std::shared_ptr<void> & nativeRef, const void * owner = nullptr, int kind = 0);

			template<typename T>
			std::shared_ptr<T> removeNativeRef(JNIEnv * env, const JavaLocalRef<jobject> & javaRef, const void * owner = nullptr, int kind = 0)
			{
				auto className = JavaClassUtils::toNativeClassName(env, javaRef);

				std::lock_guard<std::mutex> lock(objectMapMutex);

				auto it = objectMap.find(className);

				if (it == objectMap.end()) {
					return nullptr;
				}

				auto & list = it->second;
				std::shared_ptr<T> nativeRef = nullptr;

				for (auto it = list.begin(); it != list.end(); ++it) {
					if (it->owner == owner && it->kind == kind && env->IsSameObject(it->javaRef, javaRef)) {
						nativeRef = std::static_pointer_cast<T>(it->nativeRef);
						list.erase(it);
						break;
					}
				}

				return nativeRef;
			}

			// Removes every entry of the owner, of all kinds, which must all hold a
			// native object of type T.
			template<typename T>
			std::vector<std::shared_ptr<T>> removeNativeRefs(const void * owner)
			{
				std::vector<std::shared_ptr<T>> nativeRefs;

				std::lock_guard<std::mutex> lock(objectMapMutex);

				for (auto & entry : objectMap) {
					auto & list = entry.second;

					for (auto it = list.begin(); it != list.end();) {
						if (it->owner == owner) {
							nativeRefs.push_back(std::static_pointer_cast<T>(it->nativeRef));
							it = list.erase(it);
						}
						else {
							++it;
						}
					}
				}

				return nativeRefs;
			}

		private:
			struct NativeRef
			{
				JavaGlobalRef<jobject> javaRef;
				const void * owner;
				int kind;
				std::shared_ptr<void> nativeRef;
			};

			JavaVM * vm;
			// Java object class name mapped to the registrations of Java objects of that class.
			std::unordered_map<std::string, std::list<NativeRef>> objectMap;
			// Listeners are added and removed on any Java thread.
			std::mutex objectMapMutex;
	};
}

extern jni::JavaContext * javaContext;

#endif