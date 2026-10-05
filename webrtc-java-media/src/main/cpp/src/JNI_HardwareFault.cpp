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

#include "JNI_HardwareFault.h"
#include "media/HardwareFault.h"

extern "C" {
#include <libavutil/error.h>
}

// The Java class this belongs to exists in the tests only, so that nothing but
// them can reach the native side of a fault.

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_player_HardwareFault_failSend
(JNIEnv * env, jclass caller, jboolean invalidData, jint afterPackets)
{
	// Bad data is the stream's fault; anything else the hardware's.
	ffmpeg::HardwareFault::FailSend(invalidData == JNI_TRUE ? AVERROR_INVALIDDATA : AVERROR_EXTERNAL,
			afterPackets);
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_player_HardwareFault_failDrain
(JNIEnv * env, jclass caller)
{
	ffmpeg::HardwareFault::FailDrain(AVERROR_EXTERNAL);
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_player_HardwareFault_disarm
(JNIEnv * env, jclass caller)
{
	ffmpeg::HardwareFault::Disarm();
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_media_player_HardwareFault_softwareThreads
(JNIEnv * env, jclass caller)
{
	return ffmpeg::HardwareFault::SoftwareThreads();
}
