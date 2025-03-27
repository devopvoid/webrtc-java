/*
 * Copyright 2021 Alex Andres
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

#include "JNI_VoiceActivityDetector.h"

#include "JavaObject.h"
#include "JavaUtils.h"

#include "modules/audio_processing/vad/voice_activity_detector.h"

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_VoiceActivityDetector_process
(JNIEnv * env, jobject caller, jbyteArray data, jint sampleCount, jint sampleRate)
{
    webrtc::VoiceActivityDetector * activityDetector = GetHandle<webrtc::VoiceActivityDetector>(env, caller);
	CHECK_HANDLE(activityDetector);

	jbyte * dataPtr = env->GetByteArrayElements(data, nullptr);
	const int16_t * data16Ptr = reinterpret_cast<const int16_t *>(dataPtr);

	activityDetector->ProcessChunk(data16Ptr, sampleCount, sampleRate);

	env->ReleaseByteArrayElements(data, dataPtr, JNI_ABORT);
}

JNIEXPORT jfloat JNICALL Java_dev_onvoid_webrtc_media_audio_VoiceActivityDetector_getLastVoiceProbability
(JNIEnv * env, jobject caller)
{
    webrtc::VoiceActivityDetector * activityDetector = GetHandle<webrtc::VoiceActivityDetector>(env, caller);
	CHECK_HANDLEV(activityDetector, 0);

	return activityDetector->last_voice_probability();
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_VoiceActivityDetector_dispose
(JNIEnv * env, jobject caller)
{
    webrtc::VoiceActivityDetector * activityDetector = GetHandle<webrtc::VoiceActivityDetector>(env, caller);
    CHECK_HANDLE(activityDetector);

    SetHandle<std::nullptr_t>(env, caller, nullptr);

    delete activityDetector;
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_VoiceActivityDetector_initialize
(JNIEnv * env, jobject caller)
{
    webrtc::VoiceActivityDetector * activityDetector = new webrtc::VoiceActivityDetector();

    SetHandle(env, caller, activityDetector);
}