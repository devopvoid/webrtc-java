/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#include "Exception.h"
#include "JavaUtils.h"
#include "JavaClassLoader.h"
#include "JavaContext.h"
#include "JavaError.h"
#include "JavaIterable.h"
#include "JavaIOException.h"
#include "JavaString.h"
#include "JavaThreadEnv.h"
#include "JavaWrappedException.h"

#include <exception>
#include <functional>
#include <ios>
#include <map>
#include <mutex>

bool ExceptionCheck(JNIEnv * env)
{
	if (env->ExceptionCheck()) {
		jthrowable exception = env->ExceptionOccurred();
		env->ExceptionDescribe();
		env->ExceptionClear();

		if (exception) {
			throw jni::JavaWrappedException(jni::JavaLocalRef<jthrowable>(env, exception));
		}

		return true;
	}

	return false;
}

void ThrowJavaException(JNIEnv * env, const char * className, const char * message) noexcept
{
	if (env->ExceptionCheck()) {
		return;
	}

	jni::JavaLocalRef<jclass> cls(env, env->FindClass(className));

	if (cls.get() != nullptr) {
		env->ThrowNew(cls.get(), message);
	}
}

void ThrowCxxJavaException(JNIEnv * env)
{
	std::exception_ptr eptr = std::current_exception();

	if (!eptr) {
		return;
	}

	try {
		std::rethrow_exception(eptr);
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
		env->Throw(jni::JavaError(env, "Unhandled Exception"));
	}
}

jclass FindClass(JNIEnv * env, const char * name)
{
	jclass cls = reinterpret_cast<jclass>(env->NewGlobalRef(jni::ClassLoaderGetClass(env, name)));

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

namespace
{
	struct HandleClass
	{
		jclass cls;
		std::map<std::string, jfieldID> fields;
	};

	struct HandleClasses
	{
		std::mutex mutex;
		std::map<std::string, HandleClass, std::less<>> classes;
	};

	HandleClasses & GetHandleClasses()
	{
		// Never freed: the global class references keep the field IDs valid, and native
		// threads may still look up handles while the process exits.
		static HandleClasses * handleClasses = new HandleClasses();

		return *handleClasses;
	}
}

jfieldID GetHandleField(JNIEnv * env, jobject obj, const std::string & fieldName,
	const char * declaringClass)
{
	HandleClasses & handles = GetHandleClasses();
	jclass cls = nullptr;
	jfieldID field = nullptr;

	{
		std::lock_guard<std::mutex> lock(handles.mutex);

		auto entry = handles.classes.find(declaringClass);

		if (entry != handles.classes.end()) {
			cls = entry->second.cls;

			auto id = entry->second.fields.find(fieldName);

			if (id != entry->second.fields.end()) {
				field = id->second;
			}
		}
	}

	// Resolved without holding the lock, as loading a class calls into Java.
	if (cls == nullptr) {
		auto localCls = jni::ClassLoaderGetClass(env, declaringClass);

		if (localCls.get() == nullptr) {
			ExceptionCheck(env);
			return nullptr;
		}

		jclass globalCls = static_cast<jclass>(env->NewGlobalRef(localCls.get()));

		std::lock_guard<std::mutex> lock(handles.mutex);

		auto entry = handles.classes.emplace(declaringClass, HandleClass { globalCls, {} });

		if (!entry.second) {
			env->DeleteGlobalRef(globalCls);
		}

		cls = entry.first->second.cls;
	}

	if (field == nullptr) {
		field = GetFieldID(env, cls, fieldName, "J");

		if (field == nullptr) {
			return nullptr;
		}

		std::lock_guard<std::mutex> lock(handles.mutex);

		handles.classes.find(declaringClass)->second.fields.emplace(fieldName, field);
	}

	// The field ID is only valid on instances of the class that declares it.
	if (obj == nullptr) {
		ThrowJavaException(env, "java/lang/NullPointerException", "Object is null");
		return nullptr;
	}
	if (!env->IsInstanceOf(obj, cls)) {
		ThrowJavaException(env, "java/lang/IllegalArgumentException", "Object holds no native handle");
		return nullptr;
	}

	return field;
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
