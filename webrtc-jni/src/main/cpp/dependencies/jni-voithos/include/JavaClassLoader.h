/*
 * Copyright (c) 2021, Alex Andres. All rights reserved.
 *
 * Use of this source code is governed by the 3-Clause BSD license that can be
 * found in the LICENSE file in the root of the source tree.
 */

#ifndef JNI_JAVA_CLASSLOADER_H_
#define JNI_JAVA_CLASSLOADER_H_

#include "JavaRef.h"

#include <jni.h>

namespace jni
{
	void InitClassLoader(JNIEnv * env, const JavaGlobalRef<jobject> & classLoader);

	JavaLocalRef<jclass> ClassLoaderGetClass(JNIEnv * env, const char * name);
}

#endif