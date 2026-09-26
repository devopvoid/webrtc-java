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

#include "JNI_FFmpeg.h"

extern "C" {
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
}

JNIEXPORT jstring JNICALL Java_dev_onvoid_webrtc_media_ffmpeg_FFmpeg_version
(JNIEnv * env, jclass caller)
{
	// The version of the libraries that were actually loaded, which is not
	// necessarily the one this module was built against: the LGPL lets an
	// application replace them.
	return env->NewStringUTF(av_version_info());
}

JNIEXPORT jstring JNICALL Java_dev_onvoid_webrtc_media_ffmpeg_FFmpeg_license
(JNIEnv * env, jclass caller)
{
	return env->NewStringUTF(avformat_license());
}
