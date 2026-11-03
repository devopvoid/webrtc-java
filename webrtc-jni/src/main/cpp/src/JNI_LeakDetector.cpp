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

#include "JNI_LeakDetector.h"
#include "JNI_WebRTC.h"
#include "LeakDetection.h"
#include "JavaUtils.h"

#include <atomic>

namespace
{
	// Set before enabled, which publishes them.
	jclass nativeObjectClass = nullptr;
	jmethodID boundMethod = nullptr;
	jmethodID releasedMethod = nullptr;

	std::atomic<bool> enabled { false };

	void Notify(JNIEnv * env, jobject object, jmethodID method) noexcept
	{
		if (object == nullptr) {
			return;
		}

		// The caller's exception, if any, stays: it is not this one's to report.
		jthrowable pending = env->ExceptionOccurred();

		if (pending != nullptr) {
			env->ExceptionClear();
		}

		// Some objects with a field of the same name are no NativeObject.
		if (env->IsInstanceOf(object, nativeObjectClass)) {
			env->CallVoidMethod(object, method);

			// Detection must not break what it watches.
			if (env->ExceptionCheck()) {
				env->ExceptionClear();
			}
		}

		if (pending != nullptr) {
			env->Throw(pending);
			env->DeleteLocalRef(pending);
		}
	}

	void OnNativeHandleSet(JNIEnv * env, jobject object, bool bound)
	{
		Notify(env, object, bound ? boundMethod : releasedMethod);
	}
}

namespace jni
{
	namespace LeakDetection
	{
		void Released(JNIEnv * env, jobject object) noexcept
		{
			if (enabled.load(std::memory_order_acquire)) {
				Notify(env, object, releasedMethod);
			}
		}
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_internal_LeakDetector_enableNative
(JNIEnv * env, jclass)
{
	if (enabled.load()) {
		return;
	}

	jclass cls = env->FindClass(PKG_INTERNAL "NativeObject");

	if (cls == nullptr) {
		return;
	}

	jmethodID bound = env->GetMethodID(cls, "nativeHandleBound", "()V");

	if (bound == nullptr) {
		env->DeleteLocalRef(cls);
		return;
	}

	jmethodID released = env->GetMethodID(cls, "nativeHandleReleased", "()V");

	if (released == nullptr) {
		env->DeleteLocalRef(cls);
		return;
	}

	nativeObjectClass = static_cast<jclass>(env->NewGlobalRef(cls));
	env->DeleteLocalRef(cls);

	if (nativeObjectClass == nullptr) {
		return;
	}

	boundMethod = bound;
	releasedMethod = released;

	enabled.store(true, std::memory_order_release);

	SetNativeHandleHook(&OnNativeHandleSet);
}
