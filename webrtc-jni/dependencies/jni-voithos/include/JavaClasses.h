/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#ifndef JNI_JAVA_CLASSES_H_
#define JNI_JAVA_CLASSES_H_

#include "JavaClass.h"

#include <jni.h>
#include <memory>
#include <mutex>
#include <typeindex>
#include <unordered_map>

namespace jni
{
	class JavaClasses
	{
		public:
			template <typename T, typename = std::enable_if_t<std::is_base_of<JavaClass, T>::value>>
			static const std::shared_ptr<T> get(JNIEnv * env)
			{
				std::lock_guard<std::mutex> l(getMutex());

				auto & map = getClassMap();
				const std::type_index index = std::type_index(typeid(T));

				auto found = map.find(index);
				if (found == map.end()) {
					auto cls = std::make_shared<T>(env);

					map.emplace(index, cls);

					return cls;
				}

				return std::static_pointer_cast<T>(found->second);
			}

		private:
			static std::unordered_map<std::type_index, std::shared_ptr<JavaClass>> & getClassMap()
			{
				static std::unordered_map<std::type_index, std::shared_ptr<JavaClass>> map;
				return map;
			}

			static std::mutex & getMutex()
			{
				static std::mutex mutex;
				return mutex;
			}

		private:
			JavaClasses() = default;
			~JavaClasses() = default;
	};
}

#endif