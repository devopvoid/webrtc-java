/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#ifndef JNI_JAVA_THROWABLE_H_
#define JNI_JAVA_THROWABLE_H_

#include "JavaClass.h"
#include "JavaClasses.h"

#include <jni.h>
#include <string>
#include <typeindex>
#include <unordered_map>

namespace jni
{
	class JavaThrowable
	{
		public:
			class JavaThrowableClass : public JavaClass
			{
				public:
					explicit JavaThrowableClass(JNIEnv * env);
					JavaThrowableClass(JNIEnv * env, const char * className);

					jclass cls;
					jmethodID ctor;
			};

		public:
			JavaThrowable(JNIEnv * env, const char * message, ...);
			virtual ~JavaThrowable() = default;

			virtual operator jthrowable() const;

		protected:
			template <typename T, typename = std::enable_if_t<std::is_base_of<JavaThrowableClass, T>::value>>
			jthrowable createThrowable() const
			{
				const auto classDef = JavaClasses::get<T>(env);

				jobject throwable = env->NewObject(classDef->cls, classDef->ctor, env->NewStringUTF(message.c_str()));

				return static_cast<jthrowable>(throwable);
			}

		private:
			JNIEnv * env;
			std::string message;

			static const uint16_t MAX_MESSAGE_LEN = 1024;
	};
}

#endif