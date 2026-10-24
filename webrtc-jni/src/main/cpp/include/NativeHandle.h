/*
 * Copyright 2026 Alex Andres
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

#ifndef JNI_WEBRTC_NATIVE_HANDLE_H_
#define JNI_WEBRTC_NATIVE_HANDLE_H_

#include "JNI_WebRTC.h"
#include "JavaUtils.h"

#include <jni.h>

// The class declaring the field that binds a native object to a Java object. A handle is
// always looked up on its declaring class, as a subclass may declare a field of the same name.
#define NATIVE_OBJECT_CLASS PKG_INTERNAL "NativeObject"

// Returns the native object bound to the NativeObject `obj`.
template<typename T>
T * GetHandle(JNIEnv * env, jobject obj)
{
	return GetHandle<T>(env, obj, "nativeHandle", NATIVE_OBJECT_CLASS);
}

// Binds `t` to the NativeObject `obj`.
template<typename T>
void SetHandle(JNIEnv * env, jobject obj, T * t)
{
	SetHandle<T>(env, obj, "nativeHandle", t, NATIVE_OBJECT_CLASS);
}

#endif
