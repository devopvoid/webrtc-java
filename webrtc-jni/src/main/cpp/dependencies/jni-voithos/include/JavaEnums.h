/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#ifndef JNI_JAVA_ENUMS_H_
#define JNI_JAVA_ENUMS_H_

#include "JavaEnum.h"
#include "JavaRef.h"
#include "JavaUtils.h"
#include "Exception.h"

#include <jni.h>
#include <typeindex>
#include <unordered_map>

#ifdef __cpp_lib_any
#include <any>
#endif

namespace jni
{
	class JavaEnums
	{
		public:
			JavaEnums() = default;
			~JavaEnums() = default;

#ifdef __cpp_lib_any
			template <class T>
			static void add(JNIEnv * env, const char * className)
			{
				std::unordered_map<std::type_index, std::any> & map = getEnumMap();

				map.emplace(std::type_index(typeid(T)), std::make_any<JavaEnum<T>>(JavaEnum<T>(env, className)));
			}

			template <class T>
			static JavaLocalRef<jobject> toJava(JNIEnv * env, const T & nativeType)
			{
				std::unordered_map<std::type_index, std::any> & map = getEnumMap();
				std::type_index index = std::type_index(typeid(T));

				auto found = map.find(index);
				if (found == map.end()) {
					throw Exception("JavaEnum for [%s] was not registered", typeid(T).name());
				}

				const JavaEnum<T> & e = std::any_cast<JavaEnum<T>>(found->second);

				return e.toJava(env, nativeType);
			}

			template <class T>
			static T toNative(JNIEnv * env, const jobject & javaType)
			{
				std::unordered_map<std::type_index, std::any> & map = getEnumMap();
				std::type_index index = std::type_index(typeid(T));

				auto found = map.find(index);
				if (found == map.end()) {
					throw Exception("JavaEnum for [%s] was not registered", typeid(T).name());
				}

				const JavaEnum<T> & e = std::any_cast<JavaEnum<T>>(found->second);

				return e.toNative(env, javaType);
			}

		private:
			static std::unordered_map<std::type_index, std::any> & getEnumMap()
			{
				static std::unordered_map<std::type_index, std::any> map;
				return map;
			}
#else
			template <class T>
			static void add(JNIEnv * env, const char * className)
			{
				std::unordered_map<std::type_index, unique_void_ptr> & map = getEnumMap();

				map.emplace(std::type_index(typeid(T)), make_unique_void(new JavaEnum<T>(env, className)));
			}

			template <class T>
			static JavaLocalRef<jobject> toJava(JNIEnv * env, const T & nativeType)
			{
				std::unordered_map<std::type_index, unique_void_ptr> & map = getEnumMap();
				std::type_index index = std::type_index(typeid(T));

				auto found = map.find(index);
				if (found == map.end()) {
					throw Exception("JavaEnum for [%s] was not registered", typeid(T).name());
				}

				const unique_void_ptr & p = found->second;
				JavaEnum<T> * e = static_cast<JavaEnum<T> *>(p.get());

				return JavaLocalRef<jobject>(env, e->toJava(env, nativeType));
			}

			template <class T>
			static T toNative(JNIEnv * env, const jobject & javaType)
			{
				std::unordered_map<std::type_index, unique_void_ptr> & map = getEnumMap();
				std::type_index index = std::type_index(typeid(T));

				auto found = map.find(index);
				if (found == map.end()) {
					throw Exception("JavaEnum for [%s] was not registered", typeid(T).name());
				}

				const unique_void_ptr & p = found->second;
				JavaEnum<T> * e = static_cast<JavaEnum<T> *>(p.get());

				return e->toNative(env, javaType);
			}

		private:
			static std::unordered_map<std::type_index, unique_void_ptr> & getEnumMap()
			{
				static std::unordered_map<std::type_index, unique_void_ptr> map;
				return map;
			}
#endif
	};
}

#endif