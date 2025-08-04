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

#include "JNI_SyncClock.h"
#include "JavaUtils.h"
#include "media/SyncClock.h"

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_SyncClock_initialize
(JNIEnv * env, jobject caller)
{
    jni::SyncClock * clock = new jni::SyncClock();
    
    SetHandle(env, caller, clock);
}

JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_media_SyncClock_getTimestampUs
(JNIEnv * env, jobject caller)
{
    jni::SyncClock * clock = GetHandle<jni::SyncClock>(env, caller);
    CHECK_HANDLEV(clock, 0);
    
    return clock->GetTimestampUs();
}

JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_media_SyncClock_getTimestampMs
(JNIEnv * env, jobject caller)
{
    jni::SyncClock * clock = GetHandle<jni::SyncClock>(env, caller);
    CHECK_HANDLEV(clock, 0);
    
    return clock->GetTimestampMs();
}

JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_media_SyncClock_getNtpTime
(JNIEnv * env, jobject caller)
{
    jni::SyncClock * clock = GetHandle<jni::SyncClock>(env, caller);
    CHECK_HANDLEV(clock, 0);
    
    webrtc::NtpTime ntpTime = clock->GetNtpTime();
    return ntpTime.ToMs();
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_SyncClock_dispose
(JNIEnv * env, jobject caller)
{
    jni::SyncClock * clock = GetHandle<jni::SyncClock>(env, caller);
    CHECK_HANDLE(clock);
    
    delete clock;
    
    SetHandle<std::nullptr_t>(env, caller, nullptr);
}