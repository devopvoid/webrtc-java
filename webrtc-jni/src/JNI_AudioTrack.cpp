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

#include "JNI_AudioTrack.h"
#include "api/AudioTrackSink.h"
#include "JavaNullPointerException.h"
#include "JavaUtils.h"

#include "api/media_stream_interface.h"

JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_media_audio_AudioTrack_addSinkInternal
(JNIEnv * env, jobject caller, jobject jsink)
{
	if (jsink == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "AudioTrackSink must not be null"));
		return 0;
	}

	webrtc::AudioTrackInterface * track = GetHandle<webrtc::AudioTrackInterface>(env, caller);
	CHECK_HANDLEV(track, 0);

	auto sink = new jni::AudioTrackSink(env, jni::JavaGlobalRef<jobject>(env, jsink));

	track->AddSink(sink);

	return reinterpret_cast<jlong>(sink);
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioTrack_removeSinkInternal
(JNIEnv * env, jobject caller, jlong sinkHandle)
{
	webrtc::AudioTrackInterface * track = GetHandle<webrtc::AudioTrackInterface>(env, caller);
	CHECK_HANDLE(track);

	auto sink = reinterpret_cast<jni::AudioTrackSink *>(sinkHandle);

	if (sink != nullptr) {
		track->RemoveSink(sink);

		delete sink;
	}
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_media_audio_AudioTrack_getSignalLevel
(JNIEnv * env, jobject caller)
{
	int level = 0;

	webrtc::AudioTrackInterface * track = GetHandle<webrtc::AudioTrackInterface>(env, caller);
	CHECK_HANDLEV(track, level);

	track->GetSignalLevel(&level);

	return static_cast<jint>(level);
}