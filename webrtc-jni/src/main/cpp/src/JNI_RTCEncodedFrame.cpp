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

#include "JNI_RTCEncodedFrame.h"

#include "api/frame_transformer_interface.h"

#include <cstring>
#include <span>

// The Java side checks the handle, the calling thread, the bounds and the
// capacity before calling any of these, since it can throw the right
// exception for each; what is left to check here is what only native code
// can see.

namespace
{
	webrtc::TransformableFrameInterface * FrameOf(jlong handle)
	{
		return reinterpret_cast<webrtc::TransformableFrameInterface *>(handle);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCEncodedFrame_copyData
(JNIEnv * env, jclass caller, jlong handle, jobject target)
{
	webrtc::TransformableFrameInterface * frame = FrameOf(handle);

	if (frame == nullptr) {
		return;
	}

	uint8_t * address = static_cast<uint8_t *>(env->GetDirectBufferAddress(target));
	jlong capacity = env->GetDirectBufferCapacity(target);
	std::span<const uint8_t> data = frame->GetData();

	if (address != nullptr && !data.empty() && capacity >= static_cast<jlong>(data.size())) {
		std::memcpy(address, data.data(), data.size());
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCEncodedFrame_setDataBuffer
(JNIEnv * env, jclass caller, jlong handle, jobject source, jint offset, jint length)
{
	webrtc::TransformableFrameInterface * frame = FrameOf(handle);
	const uint8_t * address = static_cast<const uint8_t *>(env->GetDirectBufferAddress(source));

	if (frame == nullptr || address == nullptr) {
		return;
	}

	frame->SetData(std::span<const uint8_t>(address + offset, static_cast<size_t>(length)));
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_RTCEncodedFrame_setDataArray
(JNIEnv * env, jclass caller, jlong handle, jbyteArray source, jint offset, jint length)
{
	webrtc::TransformableFrameInterface * frame = FrameOf(handle);

	if (frame == nullptr) {
		return;
	}

	// SetData() copies, so the array stays pinned for no longer than that.
	void * elements = env->GetPrimitiveArrayCritical(source, nullptr);

	if (elements == nullptr) {
		return;
	}

	const uint8_t * address = static_cast<const uint8_t *>(elements) + offset;

	frame->SetData(std::span<const uint8_t>(address, static_cast<size_t>(length)));

	env->ReleasePrimitiveArrayCritical(source, elements, JNI_ABORT);
}
