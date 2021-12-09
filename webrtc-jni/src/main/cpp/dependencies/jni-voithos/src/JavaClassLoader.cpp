/*
 * Copyright (c) 2021, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#include "JavaClassLoader.h"
#include "JavaString.h"
#include "JavaUtils.h"

#include <algorithm>
#include <string>

namespace jni
{
	class JavaClassLoader {
		public:
			explicit JavaClassLoader(JNIEnv * env, const JavaGlobalRef<jobject> & classLoader) :
				classLoader(classLoader)
			{
				classLoaderClass_ = reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("java/lang/ClassLoader")));

				if (ExceptionCheck(env)) {
					return;
				}

				loadClass = env->GetMethodID(classLoaderClass_, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");

				ExceptionCheck(env);
			}

			JavaLocalRef<jclass> FindClass(JNIEnv * env, const char * c_name)
			{
				std::string name(c_name);
				std::replace(name.begin(), name.end(), '/', '.');

				JavaLocalRef<jstring> j_name = JavaString::toJava(env, name);

				const jclass cls = static_cast<jclass>(env->CallObjectMethod(classLoader.get(), loadClass, j_name.get()));

				if (ExceptionCheck(env)) {
					return nullptr;
				}

				return JavaLocalRef<jclass>(env, cls);
			}

		private:
			JavaGlobalRef<jobject> classLoader;
			jclass classLoaderClass_;
			jmethodID loadClass;
	};


	static JavaClassLoader * javaClassLoader = nullptr;


	void InitClassLoader(JNIEnv * env, const JavaGlobalRef<jobject> & classLoader) {
		if (javaClassLoader == nullptr) {
			javaClassLoader = new JavaClassLoader(env, classLoader);
		}
	}

	JavaLocalRef<jclass> ClassLoaderGetClass(JNIEnv * env, const char * name) {
		return (javaClassLoader == nullptr)
					? JavaLocalRef<jclass>(env, env->FindClass(name))
					: javaClassLoader->FindClass(env, name);
	}
}
