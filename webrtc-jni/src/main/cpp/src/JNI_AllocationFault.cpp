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
#include "AllocationFault.h"

#include "api/EncodedFrameTransformer.h"
#include "api/ExtensionApi.h"

namespace
{
	void IgnoreFrame(void * opaque, const wj_encoded_frame * frame)
	{
	}
}

// The Java class this belongs to exists in the tests only, so that nothing but
// them can make an allocation fail.

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_internal_AllocationFault_failNextListener
(JNIEnv * env, jclass caller)
{
	jni::AllocationFault::FailNextListener();
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_internal_AllocationFault_failNextEncodedObserver
(JNIEnv * env, jclass caller)
{
	jni::AllocationFault::FailNextEncodedObserver();
}

JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_internal_AllocationFault_addEncodedObserver
(JNIEnv * env, jclass caller, jlong frames)
{
	return reinterpret_cast<jlong>(jni::GetExtensionApi()->encoded_observer_add(reinterpret_cast<void *>(frames), &IgnoreFrame, nullptr));
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_internal_AllocationFault_removeEncodedObserver
(JNIEnv * env, jclass caller, jlong observer)
{
	jni::GetExtensionApi()->encoded_observer_remove(reinterpret_cast<void *>(observer));
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_internal_AllocationFault_encodedObservers
(JNIEnv * env, jclass caller, jlong frames)
{
	return static_cast<jint>(reinterpret_cast<jni::EncodedFrameTransformer *>(frames)->GetObserverCount());
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_internal_AllocationFault_disarm
(JNIEnv * env, jclass caller)
{
	jni::AllocationFault::Disarm();
}
