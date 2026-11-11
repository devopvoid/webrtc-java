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
#include <type_traits>
#include <typeindex>
#include <typeinfo>
#include <unordered_map>
#include <utility>

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

			// Creates the Java object for what `owner` points to and moves the
			// reference `owner` holds into it, for a Java object that owns and
			// disposes its native object. The reference is handed over only once
			// the Java object exists and holds the handle: if creating it fails,
			// `owner` keeps the reference and frees it as usual, and the Java
			// object, which nothing can reach yet, is left to the garbage
			// collector. `owner` is a smart pointer with get() and release(), such
			// as webrtc::scoped_refptr or std::unique_ptr, passed with std::move.
			template <class Ptr>
			static JavaLocalRef<jobject> createOwning(JNIEnv * env, Ptr && owner)
			{
				return createOwning(env, std::forward<Ptr>(owner), [](jobject) {});
			}

			// Same as above, and calls `bind` with the new Java object before the
			// reference is handed over, so that whatever else has to be bound to
			// the Java object (e.g. the handle of an observer) is bound before the
			// Java object owns anything. If `bind` throws, `owner` keeps the
			// reference.
			template <class Ptr, class Bind>
			static JavaLocalRef<jobject> createOwning(JNIEnv * env, Ptr && owner, Bind && bind)
			{
				static_assert(!std::is_lvalue_reference<Ptr>::value,
					"Pass the owner with std::move, the call takes its reference");

				using T = typename std::remove_cv<typename std::remove_pointer<decltype(owner.get())>::type>::type;

				JavaLocalRef<jobject> object = create<T>(env, owner.get());

				if (object.get() == nullptr) {
					throw Exception("Create Java object for [%s] failed", typeid(T).name());
				}

				bind(object.get());

				// The Java object holds the reference now.
				static_cast<void>(owner.release());

				return object;
			}

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

				return JavaLocalRef<jobject>(env, f.create(env, nativeObject));
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

				return JavaLocalRef<jobjectArray>(env, f.createArray(env, length));
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

				unique_void_ptr & p = found->second;
				const JavaFactory<T> * f = static_cast<JavaFactory<T> *>(p.get());

				return JavaLocalRef<jobject>(env, f->create(env, nativeObject));
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

				unique_void_ptr & p = found->second;
				const JavaFactory<T> * f = static_cast<JavaFactory<T> *>(p.get());

				return JavaLocalRef<jobjectArray>(env, f->createArray(env, length));
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
