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

#ifndef JNI_SYSTEM_AUDIO_SOURCE_H
#define JNI_SYSTEM_AUDIO_SOURCE_H
#include <jni.h>
extern "C" {
JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_SystemAudioSource_00024Capture_initialize(JNIEnv *, jobject);
JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_SystemAudioSource_00024Capture_pump(JNIEnv *, jobject, jobject);
JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_SystemAudioSource_00024Capture_dispose(JNIEnv *, jobject);
}
#endif
