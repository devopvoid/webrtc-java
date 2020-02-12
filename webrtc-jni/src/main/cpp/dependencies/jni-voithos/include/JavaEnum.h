/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#ifndef JNI_JAVA_ENUM_H_
#define JNI_JAVA_ENUM_H_

#include "JavaClass.h"
#include "JavaError.h"
#include "JavaRef.h"
#include "JavaUtils.h"

#include <jni.h>

namespace jni
{
	template <class T>
	class JavaEnum
	{
		private:
			class JavaEnumClass : public JavaClass
			{
				public:
					explicit JavaEnumClass(JNIEnv * env)
					{
						jclass enumClass = FindClass(env, "java/lang/Enum");

						ordinal = GetMethod(env, enumClass, "ordinal", "()I");
					}

					jmethodID ordinal;
			};

		public:
			JavaEnum(JNIEnv * env, const char * className) :
				values(env, nullptr),
				valuesLength(0)
			{
				jclass enumClass = FindClass(env, className);

				std::string valuesSig = "()[L" + std::string(className) + ";";

				jmethodID enumValues = GetStaticMethod(env, enumClass, "values", valuesSig.c_str());

				jobject val = env->CallStaticObjectMethod(enumClass, enumValues);

				values = JavaGlobalRef<jobjectArray>(env, static_cast<jobjectArray>(val));
				valuesLength = env->GetArrayLength(values.get());
			}

			~JavaEnum()
			{
			}

			jobject toJava(JNIEnv * env, const T & nativeType)
			{
				jsize index = static_cast<jsize>(nativeType);

				if (index < 0 || index >= valuesLength) {
					env->Throw(JavaError(env, "Get Java enum type failed. Index [%d] out of bounds [0,%d]", index, valuesLength));
					return nullptr;
				}

				return env->GetObjectArrayElement(values, index);
			}

			T toNative(JNIEnv * env, const jobject & javaType)
			{
				const auto enumClass = JavaClasses::get<JavaEnumClass>(env);

				int id = env->CallIntMethod(javaType, enumClass->ordinal);

				ExceptionCheck(env);

				return static_cast<T>(id);
			}

		private:
			JavaGlobalRef<jobjectArray> values;
			jsize valuesLength;
	};
}

#endif