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

#include "JNI_CustomAudioSource.h"
#include "JavaUtils.h"

#include "media/audio/CustomAudioSource.h"

#include "rtc_base/logging.h"

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_CustomAudioSource_initialize
(JNIEnv * env, jobject caller)
{
    std::shared_ptr<jni::SyncClock> sync_clock = std::make_shared<jni::SyncClock>();
    webrtc::scoped_refptr<jni::CustomAudioSource> source = webrtc::make_ref_counted<jni::CustomAudioSource>(sync_clock);

    SetHandle(env, caller, source.release());
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_CustomAudioSource_dispose
(JNIEnv * env, jobject caller)
{
    jni::CustomAudioSource * source = GetHandle<jni::CustomAudioSource>(env, caller);
    CHECK_HANDLE(source);

	webrtc::RefCountReleaseStatus status = source->Release();

	if (status != webrtc::RefCountReleaseStatus::kDroppedLastRef) {
		RTC_LOG(LS_WARNING) << "Native object was not deleted. A reference is still around somewhere.";
	}

	SetHandle<std::nullptr_t>(env, caller, nullptr);

	source = nullptr;
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_CustomAudioSource_pushAudio
(JNIEnv * env, jobject caller, jbyteArray audioData, jint bits_per_sample, jint sampleRate, jint channels, jint frameCount)
{
    jni::CustomAudioSource * source = GetHandle<jni::CustomAudioSource>(env, caller);
    CHECK_HANDLE(source);
    
    jbyte * data = env->GetByteArrayElements(audioData, nullptr);
    jsize length = env->GetArrayLength(audioData);
    
    if (data != nullptr) {
        source->PushAudioData(data, bits_per_sample, sampleRate, channels, frameCount);
        
        env->ReleaseByteArrayElements(audioData, data, JNI_ABORT);
    }
}