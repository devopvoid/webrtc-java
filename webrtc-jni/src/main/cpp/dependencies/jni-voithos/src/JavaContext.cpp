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

	void JavaContext::addNativeRef(JNIEnv * env, const JavaLocalRef<jobject> & javaRef, const std::shared_ptr<void> & nativeRef)
	{
		auto className = JavaClassUtils::toNativeClassName(env, javaRef);
		auto globalRef = JavaGlobalRef<jobject>(env, javaRef.get());
		auto pair = std::make_pair(globalRef, nativeRef);
		auto it = objectMap.find(className);

		if (it == objectMap.end()) {
			std::list<std::pair<JavaGlobalRef<jobject>, std::shared_ptr<void>>> list = { pair };

			objectMap[className] = list;
		}
		else {
			it->second.push_back(pair);
		}
	}
}