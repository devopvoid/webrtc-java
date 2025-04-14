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

#include "JNI_RefCountedObject.h"
#include "JavaUtils.h"

#include "rtc_base/ref_count.h"

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_internal_RefCountedObject_retain
(JNIEnv * env, jobject caller)
{
	webrtc::RefCountInterface * ref = GetHandle<webrtc::RefCountInterface>(env, caller);
	CHECK_HANDLE(ref);

	ref->AddRef();
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_internal_RefCountedObject_release
(JNIEnv * env, jobject caller)
{
	webrtc::RefCountInterface * ref = GetHandle<webrtc::RefCountInterface>(env, caller);
	CHECK_HANDLE(ref);

	const webrtc::RefCountReleaseStatus status = ref->Release();

	if (status == webrtc::RefCountReleaseStatus::kDroppedLastRef) {
		SetHandle<std::nullptr_t>(env, caller, nullptr);
	}
}