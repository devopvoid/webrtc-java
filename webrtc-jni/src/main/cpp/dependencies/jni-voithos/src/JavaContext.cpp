/*
 * Copyright (c) 2019, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#include "JavaContext.h"

namespace jni
{
	JavaContext::JavaContext(JavaVM * vm) :
		vm(vm)
	{
	}

	JavaVM * JavaContext::getVM()
	{
		return vm;
	}

	void JavaContext::addNativeRef(JNIEnv * env, const JavaLocalRef<jobject> & javaRef, const std::shared_ptr<void> & nativeRef, const void * owner, int kind)
	{
		auto className = JavaClassUtils::toNativeClassName(env, javaRef);
		NativeRef entry = { JavaGlobalRef<jobject>(env, javaRef.get()), owner, kind, nativeRef };

		std::lock_guard<std::mutex> lock(objectMapMutex);

		objectMap[className].push_back(std::move(entry));
	}
}