/*
 * Copyright 2019 Alex Andres
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "JNI_WebRTC.h"
#include "JavaContext.h"
#include "JavaUtils.h"
#include "WebRTCContext.h"

#include <jni.h>

jni::JavaContext * javaContext = nullptr;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM * vm, void * reserved)
{
	JNIEnv * env = nullptr;

	if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
		return -1;
	}

	javaContext = new jni::WebRTCContext(vm);

	try {
		javaContext->initialize(env);
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}

	return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM * vm, void * reserved)
{
	if (javaContext != nullptr) {
		JNIEnv * env = nullptr;

		if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
			return;
		}

		try {
			javaContext->destroy(env);
		}
		catch (...) {
			ThrowCxxJavaException(env);
		}

		delete javaContext;
	}
}
