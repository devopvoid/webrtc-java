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
			// The created objects hold their native object in the `nativeHandle` field
			// declared by `handleClass`.
			JavaFactory(JNIEnv * env, const char * className, const char * handleClass) :
				JavaFactory(env, className, "()V", handleClass)
			{
			}

			virtual ~JavaFactory()
			{
			    javaClass.release();
			}

			virtual JavaLocalRef<jobject> create(JNIEnv * env, const T * nativeObject) const
			{
				jobject object = env->NewObject(javaClass, javaCtor);
				ExceptionCheck(env);

				SetHandle(env, object, "nativeHandle", nativeObject, handleClass);

				return JavaLocalRef<jobject>(env, object);
			}

			virtual JavaLocalRef<jobjectArray> createArray(JNIEnv * env, const jsize & length) const
			{
				return JavaLocalRef<jobjectArray>(env, env->NewObjectArray(length, javaClass, nullptr));
			}

		protected:
			// A subclass that overrides create() binds no handle and may pass no `handleClass`.
			JavaFactory(JNIEnv * env, const char * className, const char * ctorSignature,
				const char * handleClass) :
				javaClass(nullptr),
				javaCtor(nullptr),
				handleClass(handleClass)
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
			const char * handleClass;
	};
}

#endif