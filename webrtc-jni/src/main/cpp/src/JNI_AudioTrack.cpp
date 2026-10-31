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
#include "media/audio/VoiceActivitySink.h"

#include "api/media_stream_interface.h"

JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_media_audio_AudioTrack_addSinkInternal
(JNIEnv * env, jobject caller, jobject jsink, jboolean packetInfos)
{
	try {
		if (jsink == nullptr) {
			env->Throw(jni::JavaNullPointerException(env, "AudioTrackSink must not be null"));
			return 0;
		}

		webrtc::AudioTrackInterface * track = GetHandle<webrtc::AudioTrackInterface>(env, caller);
		CHECK_HANDLEV(track, 0);

		auto sink = new jni::AudioTrackSink(env, jni::JavaGlobalRef<jobject>(env, jsink), packetInfos);

		track->AddSink(sink);

		return reinterpret_cast<jlong>(sink);
	}
	catch (...) {
		ThrowCxxJavaException(env);
		return 0;
	}
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

JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_media_audio_AudioTrack_addVoiceActivityListenerInternal
(JNIEnv * env, jobject caller, jobject jListener, jfloat threshold, jlong hangoverNanos)
{
	try {
		webrtc::AudioTrackInterface * track = GetHandle<webrtc::AudioTrackInterface>(env, caller);
		CHECK_HANDLEV(track, 0);

		auto sink = new jni::VoiceActivitySink(std::make_unique<jni::VoiceActivityListener>(env,
			jni::JavaGlobalRef<jobject>(env, jListener), threshold, hangoverNanos));

		track->AddSink(sink);

		return reinterpret_cast<jlong>(sink);
	}
	catch (...) {
		ThrowCxxJavaException(env);
		return 0;
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioTrack_removeVoiceActivityListenerInternal
(JNIEnv * env, jobject caller, jlong sinkHandle)
{
	webrtc::AudioTrackInterface * track = GetHandle<webrtc::AudioTrackInterface>(env, caller);
	CHECK_HANDLE(track);

	auto sink = reinterpret_cast<jni::VoiceActivitySink *>(sinkHandle);

	if (sink != nullptr) {
		// Once removed, the source no longer calls it.
		track->RemoveSink(sink);

		delete sink;
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_audio_AudioTrack_setVolumeInternal
(JNIEnv * env, jobject caller, jdouble volume)
{
	webrtc::AudioTrackInterface * track = GetHandle<webrtc::AudioTrackInterface>(env, caller);
	CHECK_HANDLE(track);

	// Only the source of a remote track, a RemoteAudioSource, applies the
	// volume; the source of a local track ignores it.
	webrtc::AudioSourceInterface * source = track->GetSource();

	if (source != nullptr) {
		source->SetVolume(static_cast<double>(volume));
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