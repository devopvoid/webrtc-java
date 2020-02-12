/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#include "JavaArray.h"
#include "JavaClasses.h"
#include "JavaString.h"
#include "JavaUtils.h"

namespace jni
{
	namespace JavaString
	{
		std::string toNative(JNIEnv * env, const JavaRef<jstring> & jstr)
		{
			if (jstr.get() == nullptr) {
				return "";
			}

			const auto strClass = JavaClasses::get<JavaStringClass>(env);

			jbyteArray stringBytes = static_cast<jbyteArray>(env->CallObjectMethod(jstr, strClass->getBytes, env->NewStringUTF("UTF-8")));
			jsize length = env->GetArrayLength(stringBytes);

			std::string str(length, '\0');

			env->GetByteArrayRegion(stringBytes, 0, length, reinterpret_cast<jbyte *>(&str[0]));

			return str;
		}

		JavaLocalRef<jstring> toJava(JNIEnv * env, const std::string & str)
		{
			if (str.empty()) {
				return nullptr;
			}

			return JavaLocalRef<jstring>(env, env->NewStringUTF(str.c_str()));
		}

		JavaLocalRef<jobjectArray> createArray(JNIEnv * env, const std::vector<std::string> & vector)
		{
			const auto javaClass = JavaClasses::get<JavaStringClass>(env);

			return JavaArray::createObjectArray(env, vector, javaClass->cls, &toJava);
		}

		JavaStringClass::JavaStringClass(JNIEnv * env)
		{
			cls = FindClass(env, "java/lang/String");

			getBytes = GetMethod(env, cls, "getBytes", "(Ljava/lang/String;)[B");
		}
	}
}