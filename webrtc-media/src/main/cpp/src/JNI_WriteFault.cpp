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

#include "JNI_WriteFault.h"
#include "media/WriteFault.h"

#include <cerrno>

extern "C" {
#include <libavutil/error.h>
}

// The Java class this belongs to exists in the tests only, so that nothing but
// them can make a recording fail. Writing fails as on a full disk, closing as
// on a file system that loses what it was to keep.

using ffmpeg::WriteFault;

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_recorder_WriteFault_failHeader
(JNIEnv * env, jclass caller)
{
	WriteFault::Fail(WriteFault::Call::kHeader, AVERROR(ENOSPC), 0);
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_recorder_WriteFault_failPacket
(JNIEnv * env, jclass caller, jint afterPackets)
{
	WriteFault::Fail(WriteFault::Call::kPacket, AVERROR(ENOSPC), afterPackets);
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_recorder_WriteFault_failTrailer
(JNIEnv * env, jclass caller)
{
	WriteFault::Fail(WriteFault::Call::kTrailer, AVERROR(ENOSPC), 0);
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_recorder_WriteFault_failClose
(JNIEnv * env, jclass caller)
{
	WriteFault::Fail(WriteFault::Call::kClose, AVERROR(EIO), 0);
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_recorder_WriteFault_disarm
(JNIEnv * env, jclass caller)
{
	WriteFault::Disarm();
}
