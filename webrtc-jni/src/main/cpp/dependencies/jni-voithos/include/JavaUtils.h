/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#ifndef JNI_JAVA_UTILS_H_
#define JNI_JAVA_UTILS_H_

#include "JavaNullPointerException.h"

#include <functional>
#include <jni.h>
#include <memory>
#include <string>
#include <vector>


#define CHECK_HANDLE(handle)															\
	do {																				\
		if (handle == NULL) {															\
			JNIEnv * env = AttachCurrentThread();										\
			env->Throw(jni::JavaNullPointerException(env, "Object handle is null"));	\
			return;																		\
		}																				\
	} while(0)

#define CHECK_HANDLEV(handle, retValue) \
	do {																				\
		if (handle == NULL) {															\
			JNIEnv * env = AttachCurrentThread();										\
			env->Throw(jni::JavaNullPointerException(env, "Object handle is null"));	\
			return retValue;															\
		}																				\
	} while(0)

void ThrowCxxJavaException(JNIEnv * env);
JNIEnv * AttachCurrentThread();
bool ExceptionCheck(JNIEnv * env);
jclass FindClass(JNIEnv * env, const char * name);
jmethodID GetMethod(JNIEnv * env, jclass cls, const char * name, const char * sig);
jmethodID GetStaticMethod(JNIEnv * env, jclass cls, const char * name, const char * sig);
jfieldID GetHandleField(JNIEnv * env, jobject obj, const std::string & fieldName);
jfieldID GetFieldID(JNIEnv * env, jobject obj, const std::string & fieldName, const char * type);
jfieldID GetFieldID(JNIEnv * env, jclass cls, const std::string & fieldName, const char * type);


using unique_void_ptr = std::unique_ptr<void, void(*)(const void *)>;

template<typename T>
auto make_unique_void(T * ptr) -> unique_void_ptr
{
	return unique_void_ptr(ptr, [](const void * data) {
		const T * p = static_cast<const T *>(data);
		delete p;
	});
}

template<typename T>
jlong GetHandleLong(JNIEnv * env, jobject obj)
{
	jfieldID field = GetHandleField(env, obj, "nativeHandle");

	if (!field) {
		ExceptionCheck(env);
		return 0;
	}

	return env->GetLongField(obj, field);
}

template<typename T>
T * GetHandle(JNIEnv * env, jobject obj, const std::string & handleName)
{
	jfieldID field = GetHandleField(env, obj, handleName);

	if (!field) {
		ExceptionCheck(env);
		return nullptr;
	}

	return reinterpret_cast<T *>(env->GetLongField(obj, field));
}

template<typename T>
T * GetHandle(JNIEnv * env, jobject obj)
{
	return GetHandle<T>(env, obj, "nativeHandle");
}

template<typename T>
void SetHandle(JNIEnv * env, jobject obj, const std::string & handleName, T * t)
{
	jfieldID field = GetHandleField(env, obj, handleName);

	if (!field) {
		ExceptionCheck(env);
		return;
	}

	jlong handle = reinterpret_cast<jlong>(t);

	env->SetLongField(obj, field, handle);
}

template<typename T>
void SetHandle(JNIEnv * env, jobject obj, T * t)
{
	SetHandle<T>(env, obj, "nativeHandle", t);
}

#endif