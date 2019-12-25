/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#include "Exception.h"
#include "JavaUtils.h"
#include "JavaContext.h"
#include "JavaError.h"
#include "JavaIterable.h"
#include "JavaIOException.h"
#include "JavaString.h"
#include "JavaThreadEnv.h"
#include "JavaWrappedException.h"

#include <ios>

bool ExceptionCheck(JNIEnv * env)
{
	if (env->ExceptionCheck()) {
		jthrowable exception = env->ExceptionOccurred();
		env->ExceptionClear();

		if (exception) {
			throw jni::JavaWrappedException(jni::JavaLocalRef<jthrowable>(env, exception));
		}

		return true;
	}

	return false;
}

void ThrowCxxJavaException(JNIEnv * env)
{
	try {
		std::rethrow_exception(std::current_exception());
	}
	catch (const jni::JavaWrappedException & e) {
		env->Throw(e.exception().get());
	}
	catch (const std::ios_base::failure & e) {
		env->Throw(jni::JavaIOException(env, e.what()));
	}
	catch (const std::exception & e) {
		env->Throw(jni::JavaError(env, e.what()));
	}
	catch (...) {
		try {
			std::rethrow_exception(std::current_exception());
		}
		catch (const std::exception & e) {
			env->Throw(jni::JavaError(env, e.what()));
		}
	}
}

jclass FindClass(JNIEnv * env, const char * name)
{
	jclass cls = reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass(name)));

	if (cls == nullptr) {
		ExceptionCheck(env);
		return nullptr;
	}

	return cls;
}

jmethodID GetMethod(JNIEnv * env, jclass cls, const char * name, const char * sig)
{
	if (cls == nullptr) {
		return nullptr;
	}

	jmethodID method = env->GetMethodID(cls, name, sig);

	if (method == nullptr) {
		ExceptionCheck(env);
		return nullptr;
	}

	return method;
}

jmethodID GetStaticMethod(JNIEnv * env, jclass cls, const char * name, const char * sig)
{
	if (cls == nullptr) {
		return nullptr;
	}

	jmethodID method = env->GetStaticMethodID(cls, name, sig);

	if (method == nullptr) {
		ExceptionCheck(env);
		return nullptr;
	}

	return method;
}

jfieldID GetHandleField(JNIEnv * env, jobject obj, const std::string & fieldName)
{
	return GetFieldID(env, obj, fieldName, "J");
}

jfieldID GetFieldID(JNIEnv * env, jobject obj, const std::string & fieldName, const char * type)
{
	jclass cls = env->GetObjectClass(obj);

	if (cls == nullptr) {
		ExceptionCheck(env);
		return nullptr;
	}

	return GetFieldID(env, cls, fieldName, type);
}

jfieldID GetFieldID(JNIEnv * env, jclass cls, const std::string & fieldName, const char * type)
{
	jfieldID field = env->GetFieldID(cls, fieldName.c_str(), type);
	
	if (field == nullptr) {
		ExceptionCheck(env);
		return nullptr;
	}

	return field;
}

JNIEnv * AttachCurrentThread()
{
	thread_local std::unique_ptr<jni::JavaThreadEnv> threadEnv(new jni::JavaThreadEnv(javaContext->getVM()));

	return threadEnv->getEnv();
}