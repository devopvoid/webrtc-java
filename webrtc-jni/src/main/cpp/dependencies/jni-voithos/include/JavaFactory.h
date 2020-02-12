/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#ifndef JNI_JAVA_FACTORY_H_
#define JNI_JAVA_FACTORY_H_

#include "JavaRef.h"
#include "JavaUtils.h"

#include <jni.h>

namespace jni
{
	template <class T>
	class JavaFactory
	{
		public:
			JavaFactory(JNIEnv * env, const char * className) :
				JavaFactory(env, className, "()V")
			{
			}

			virtual ~JavaFactory()
			{
			}

			virtual JavaLocalRef<jobject> create(JNIEnv * env, const T * nativeObject)
			{
				jobject object = env->NewObject(javaClass, javaCtor);
				ExceptionCheck(env);

				SetHandle(env, object, nativeObject);

				return JavaLocalRef<jobject>(env, object);
			}

			virtual JavaLocalRef<jobjectArray> createArray(JNIEnv * env, const jsize & length)
			{
				return JavaLocalRef<jobjectArray>(env, env->NewObjectArray(length, javaClass, nullptr));
			}

		protected:
			JavaFactory(JNIEnv * env, const char * className, const char * ctorSignature) :
				javaClass(nullptr),
				javaCtor(nullptr)
			{
				loadClass(env, className, ctorSignature);
			}

		private:
			void loadClass(JNIEnv * env, const char * className, const char * ctorSignature)
			{
				javaClass = JavaGlobalRef<jclass>(env, FindClass(env, className));

				javaCtor = GetMethod(env, javaClass, "<init>", ctorSignature);
			}

		protected:
			JavaGlobalRef<jclass> javaClass;
			jmethodID javaCtor;
	};
}

#endif