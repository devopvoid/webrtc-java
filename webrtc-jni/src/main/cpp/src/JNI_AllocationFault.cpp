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

#include "JNI_AllocationFault.h"
#include "JavaBoundary.h"
#include "AllocationFault.h"

#include "api/EncodedFrameTransformer.h"
#include "api/ExtensionApi.h"

#include <atomic>
#include <cstdint>

namespace
{
	void IgnoreFrame(void * opaque, const wj_encoded_frame * frame)
	{
	}

	// An observer that counts the frames it sees, for the tests of what
	// observers get to see.
	struct CountingObserver
	{
		std::atomic<int64_t> frames { 0 };
		void * observer = nullptr;
	};

	void CountFrame(void * opaque, const wj_encoded_frame * frame)
	{
		static_cast<CountingObserver *>(opaque)->frames.fetch_add(1, std::memory_order_relaxed);
	}
}

// The Java class this belongs to exists in the tests only, so that nothing but
// them can make an allocation fail.

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_internal_AllocationFault_failNextNativeLease
(JNIEnv * env, jclass caller)
{
	jni::CallFromJava(env, [&]() {
		jni::AllocationFault::FailNextNativeLease();
	});
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_internal_AllocationFault_failNextListener
(JNIEnv * env, jclass caller)
{
	jni::CallFromJava(env, [&]() {
		jni::AllocationFault::FailNextListener();
	});
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_internal_AllocationFault_failNextEncodedObserver
(JNIEnv * env, jclass caller)
{
	jni::CallFromJava(env, [&]() {
		jni::AllocationFault::FailNextEncodedObserver();
	});
}

JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_internal_AllocationFault_addEncodedObserver
(JNIEnv * env, jclass caller, jlong frames)
{
	return jni::CallFromJava(env, [&]() -> jlong {
		return reinterpret_cast<jlong>(jni::GetExtensionApi()->encoded_observer_add(reinterpret_cast<void *>(frames), &IgnoreFrame, nullptr));
	});
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_internal_AllocationFault_removeEncodedObserver
(JNIEnv * env, jclass caller, jlong observer)
{
	jni::CallFromJava(env, [&]() {
		jni::GetExtensionApi()->encoded_observer_remove(reinterpret_cast<void *>(observer));
	});
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_internal_AllocationFault_encodedObservers
(JNIEnv * env, jclass caller, jlong frames)
{
	return jni::CallFromJava(env, [&]() -> jint {
		return static_cast<jint>(reinterpret_cast<jni::EncodedFrameTransformer *>(frames)->GetObserverCount());
	});
}

JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_internal_AllocationFault_addCountingObserver
(JNIEnv * env, jclass caller, jlong frames)
{
	return jni::CallFromJava(env, [&]() -> jlong {
		auto counter = new CountingObserver();
		counter->observer = jni::GetExtensionApi()->encoded_observer_add(reinterpret_cast<void *>(frames), &CountFrame, counter);

		if (counter->observer == nullptr) {
			delete counter;
			return 0;
		}

		return reinterpret_cast<jlong>(counter);
	});
}

JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_internal_AllocationFault_countedFrames
(JNIEnv * env, jclass caller, jlong counter)
{
	return jni::CallFromJava(env, [&]() -> jlong {
		return static_cast<jlong>(reinterpret_cast<CountingObserver *>(counter)->frames.load(std::memory_order_relaxed));
	});
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_internal_AllocationFault_removeCountingObserver
(JNIEnv * env, jclass caller, jlong counter)
{
	jni::CallFromJava(env, [&]() {
		auto observer = reinterpret_cast<CountingObserver *>(counter);

		// Once removed, the observer never runs again, and the counter may go.
		jni::GetExtensionApi()->encoded_observer_remove(observer->observer);
		delete observer;
	});
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_internal_AllocationFault_disarm
(JNIEnv * env, jclass caller)
{
	jni::CallFromJava(env, [&]() {
		jni::AllocationFault::Disarm();
	});
}
