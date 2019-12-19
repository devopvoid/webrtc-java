/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#ifndef JNI_JAVA_PRIMITIVE_H_
#define JNI_JAVA_PRIMITIVE_H_

#include <jni.h>

#include "JavaArray.h"
#include "JavaClass.h"
#include "JavaRef.h"
#include "JavaUtils.h"

#include <vector>

namespace jni
{
#define JAVA_PRIMITIVE_CLASS(string) Java##string##Class
#define JAVA_PRIMITIVE_OBJECT(className, retType, nType, clsPath, cSig, mName, mSig, mCall)	\
class className																	            \
{																				            \
	public:																			        \
		class JAVA_PRIMITIVE_CLASS(className) : public JavaClass                            \
		{                                                                                   \
			public:                                                                         \
				explicit JAVA_PRIMITIVE_CLASS(className)(JNIEnv * env)                      \
				{                                                                           \
					cls = FindClass(env, clsPath);                                          \
																							\
					ctor = GetMethod(env, cls, "<init>", cSig);                             \
					value = GetMethod(env, cls, mName, mSig);                               \
				}                                                                           \
																							\
				jclass cls;                                                                 \
				jmethodID ctor;                                                             \
				jmethodID value;                                                            \
		};                                                                                  \
                                                                                            \
		static JavaLocalRef<jobject> create(JNIEnv * env, nType value)                      \
		{                                                                                   \
			const auto javaClass = JavaClasses::get<JAVA_PRIMITIVE_CLASS(className)>(env);  \
			jobject obj = env->NewObject(javaClass->cls, javaClass->ctor, value);           \
			return JavaLocalRef<jobject>(env, obj);                                         \
		}                                                                                   \
                                                                                            \
		static JavaLocalRef<jobjectArray> createArray(JNIEnv * env,                         \
			const std::vector<nType> & vector)                                              \
		{                                                                                   \
			const auto javaClass = JavaClasses::get<JAVA_PRIMITIVE_CLASS(className)>(env);  \
			return JavaArray::createObjectArray(env, vector, javaClass->cls, &create);      \
		}                                                                                   \
                                                                                            \
		static retType getValue(JNIEnv * env, jobject obj)                                  \
		{                                                                                   \
			const auto javaClass = JavaClasses::get<JAVA_PRIMITIVE_CLASS(className)>(env);  \
			return env->mCall(obj, javaClass->value);										\
		}                                                                                   \
                                                                                            \
	private:                                                                                \
		className() = default;                                                              \
};                                                                                          \

	JAVA_PRIMITIVE_OBJECT(Boolean, jboolean, bool, "java/lang/Boolean", "(Z)V", "booleanValue", "()Z", CallBooleanMethod);
	JAVA_PRIMITIVE_OBJECT(Byte, jbyte, int8_t, "java/lang/Byte", "(B)V", "byteValue", "()B", CallByteMethod);
	JAVA_PRIMITIVE_OBJECT(Char, jchar, int8_t, "java/lang/Char", "(C)V", "charValue", "()C", CallCharMethod);
	JAVA_PRIMITIVE_OBJECT(Short, jshort, int16_t, "java/lang/Short", "(S)V", "shortValue", "()S", CallShortMethod);
	JAVA_PRIMITIVE_OBJECT(Integer, jint, int32_t, "java/lang/Integer", "(I)V", "intValue", "()I", CallIntMethod);
	JAVA_PRIMITIVE_OBJECT(Long, jlong, int64_t, "java/lang/Long", "(J)V", "longValue", "()J", CallLongMethod);
	JAVA_PRIMITIVE_OBJECT(Float, jfloat, float, "java/lang/Float", "(F)V", "floatValue", "()F", CallFloatMethod);
	JAVA_PRIMITIVE_OBJECT(Double, jdouble, double, "java/lang/Double", "(D)V", "doubleValue", "()D", CallDoubleMethod);
}

#endif