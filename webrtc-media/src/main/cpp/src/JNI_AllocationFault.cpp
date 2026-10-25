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
#include "media/AllocationFault.h"
#include "media/JniBoundary.h"

#include <new>
#include <stdexcept>

// The Java class this belongs to exists in the tests only, so that nothing but
// them can make an allocation fail.

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_player_AllocationFault_failNextStringCopy
(JNIEnv * env, jclass caller)
{
	ffmpeg::AllocationFault::FailNextStringCopy();
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_player_AllocationFault_failNextAllocation
(JNIEnv * env, jclass caller)
{
	ffmpeg::AllocationFault::FailNextAllocation();
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_player_AllocationFault_failNextThreadStart
(JNIEnv * env, jclass caller)
{
	ffmpeg::AllocationFault::FailNextThreadStart();
}

JNIEXPORT jboolean JNICALL Java_dev_onvoid_webrtc_media_player_AllocationFault_isArmed
(JNIEnv * env, jclass caller)
{
	return ffmpeg::AllocationFault::IsArmed() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_player_AllocationFault_throwInNative
(JNIEnv * env, jclass caller, jint kind, jstring message)
{
	ffmpeg::CallFromJava(env, [&] {
		// Held while throwing, as an entry point holds its arguments.
		ffmpeg::JavaStringChars text(env, message);

		if (text.get() == nullptr) {
			return;
		}

		switch (kind) {
			case 0:
				throw std::bad_alloc();
			case 1:
				throw std::runtime_error(text.get());
			case 2:
				// A JNI call that failed, and a C++ exception after it.
				env->FindClass("dev/onvoid/webrtc/media/player/NoSuchClass");

				throw std::runtime_error(text.get());
			default:
				throw kind;
		}
	});
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_player_AllocationFault_disarm
(JNIEnv * env, jclass caller)
{
	ffmpeg::AllocationFault::Disarm();
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_media_player_AllocationFault_heldStrings
(JNIEnv * env, jclass caller)
{
	return ffmpeg::JavaStringChars::Held();
}
