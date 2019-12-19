/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#ifndef JNI_JAVA_FACTORIES_H_
#define JNI_JAVA_FACTORIES_H_

#include "JavaFactory.h"
#include "JavaRef.h"
#include "JavaUtils.h"
#include "Exception.h"

#include <jni.h>
#include <memory>
#include <typeindex>
#include <unordered_map>

#ifdef __cpp_lib_any
#include <any>
#endif

namespace jni
{
	class JavaFactories
	{
		public:
			JavaFactories() = default;
			~JavaFactories() = default;

#ifdef __cpp_lib_any
			template <class T>
			static void add(JNIEnv * env, const char * className)
			{
				std::unordered_map<std::type_index, std::any> & map = getFactoryMap();

				map.emplace(std::type_index(typeid(T)), std::make_any<JavaFactory<T>>(JavaFactory<T>(env, className)));
			}

			template <class T>
			static void add(std::unique_ptr<JavaFactory<T>> factory)
			{
				std::unordered_map<std::type_index, std::any> & map = getFactoryMap();

				map.emplace(std::type_index(typeid(T)), std::make_any<JavaFactory<T>>(*factory.release()));
			}

			template <class T>
			static JavaLocalRef<jobject> create(JNIEnv * env, const T * nativeObject)
			{
				std::unordered_map<std::type_index, std::any> & map = getFactoryMap();
				std::type_index index = std::type_index(typeid(T));

				auto found = map.find(index);
				if (found == map.end()) {
					throw Exception("JavaFactory for [%s] was not registered", typeid(T).name());
				}

				const JavaFactory<T> & f = std::any_cast<JavaFactory<T>>(found->second);

				return f.create(env, nativeObject);
			}

			template <class T>
			static JavaLocalRef<jobjectArray> createArray(JNIEnv * env, const jsize & length)
			{
				std::unordered_map<std::type_index, std::any> & map = getFactoryMap();
				std::type_index index = std::type_index(typeid(T));

				auto found = map.find(index);
				if (found == map.end()) {
					throw Exception("JavaFactory for [%s] was not registered", typeid(T).name());
				}

				const JavaFactory<T> & f = std::any_cast<JavaFactory<T>>(found->second);

				return f.createArray(env, length);
			}

		private:
			static std::unordered_map<std::type_index, std::any> & getFactoryMap()
			{
				static std::unordered_map<std::type_index, std::any> map;
				return map;
			}
#else
			template <class T>
			static void add(JNIEnv * env, const char * className)
			{
				std::unordered_map<std::type_index, unique_void_ptr> & map = getFactoryMap();

				map.emplace(std::type_index(typeid(T)), make_unique_void(new JavaFactory<T>(env, className)));
			}

			template <class T>
			static void add(std::unique_ptr<JavaFactory<T>> factory)
			{
				std::unordered_map<std::type_index, unique_void_ptr> & map = getFactoryMap();

				map.emplace(std::type_index(typeid(T)), make_unique_void(factory.release()));
			}

			template <class T>
			static JavaLocalRef<jobject> create(JNIEnv * env, const T * nativeObject)
			{
				std::unordered_map<std::type_index, unique_void_ptr> & map = getFactoryMap();
				std::type_index index = std::type_index(typeid(T));

				auto found = map.find(index);
				if (found == map.end()) {
					throw Exception("JavaFactory for [%s] was not registered", typeid(T).name());
				}

				const unique_void_ptr & p = found->second;
				JavaFactory<T> * f = static_cast<JavaFactory<T> *>(p.get());

				return f->create(env, nativeObject);
			}

			template <class T>
			static JavaLocalRef<jobjectArray> createArray(JNIEnv * env, const jsize & length)
			{
				std::unordered_map<std::type_index, unique_void_ptr> & map = getFactoryMap();
				std::type_index index = std::type_index(typeid(T));

				auto found = map.find(index);
				if (found == map.end()) {
					throw Exception("JavaFactory for [%s] was not registered", typeid(T).name());
				}

				const unique_void_ptr & p = found->second;
				JavaFactory<T> * f = static_cast<JavaFactory<T> *>(p.get());

				return f->createArray(env, length);
			}

		private:
			static std::unordered_map<std::type_index, unique_void_ptr> & getFactoryMap()
			{
				static std::unordered_map<std::type_index, unique_void_ptr> map;
				return map;
			}
#endif
	};
}

#endif