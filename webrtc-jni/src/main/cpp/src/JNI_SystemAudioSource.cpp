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

#include "JNI_SystemAudioSource.h"
#include "JavaBoundary.h"
#include "NativeHandle.h"
#include "media/audio/SystemAudioCapture.h"

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_SystemAudioSource_00024Capture_initialize
(JNIEnv * env, jobject caller) {
    jni::CallFromJava(env, [&]() {
        auto capture = jni::SystemAudioCapture::Create();
        capture->Open();
        SetHandle(env, caller, capture.get());
        capture.release();
    });
}
JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_SystemAudioSource_00024Capture_pump
(JNIEnv * env, jobject caller, jobject javaSource) {
    jni::CallFromJava(env, [&]() {
        jni::NativeLease<jni::SystemAudioCapture> capture(env, caller);
        CHECK_HANDLE(capture);
        jni::NativeLease<jni::CustomAudioSource> source(env, javaSource);
        CHECK_HANDLE(source);
        capture->Pump(source);
    });
}
JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_SystemAudioSource_00024Capture_dispose
(JNIEnv * env, jobject caller) {
    jni::CallFromJava(env, [&]() {
        auto * capture = jni::TakeHandle<jni::SystemAudioCapture>(env, caller);
        if (capture) jni::ReleaseHandle(env, capture, [capture](bool) { delete capture; });
    });
}
