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

#include "JNI_MediaStreamTrack.h"
#include "JavaEnums.h"
#include "JavaError.h"
#include "JavaString.h"

#include "media/MediaStreamTrackObserver.h"

#include "api/media_stream_interface.h"
#include "rtc_base/logging.h"

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_MediaStreamTrack_dispose
(JNIEnv * env, jobject caller)
{
	webrtc::MediaStreamTrackInterface * track = GetHandle<webrtc::MediaStreamTrackInterface>(env, caller);
	CHECK_HANDLE(track);

	rtc::RefCountReleaseStatus status = track->Release();

	if (status != rtc::RefCountReleaseStatus::kDroppedLastRef) {
		env->Throw(jni::JavaError(env, "Native object was not deleted. A reference is still around somewhere."));
	}
	else {
		SetHandle<std::nullptr_t>(env, caller, nullptr);
		track = nullptr;
	}

	// Dispose the attached listener.
	auto listener = GetHandle<jni::MediaStreamTrackObserver>(env, caller, "listenerNativeHandle");

	if (listener) {
		delete listener;
	}
}

JNIEXPORT jstring JNICALL Java_dev_onvoid_webrtc_media_MediaStreamTrack_getKind
(JNIEnv * env, jobject caller)
{
	webrtc::MediaStreamTrackInterface * track = GetHandle<webrtc::MediaStreamTrackInterface>(env, caller);
	CHECK_HANDLEV(track, nullptr);

	return jni::JavaString::toJava(env, track->kind()).release();
}

JNIEXPORT jstring JNICALL Java_dev_onvoid_webrtc_media_MediaStreamTrack_getId
(JNIEnv * env, jobject caller)
{
	webrtc::MediaStreamTrackInterface * track = GetHandle<webrtc::MediaStreamTrackInterface>(env, caller);
	CHECK_HANDLEV(track, nullptr);

	return jni::JavaString::toJava(env, track->id()).release();
}

JNIEXPORT jboolean JNICALL Java_dev_onvoid_webrtc_media_MediaStreamTrack_isEnabled
(JNIEnv * env, jobject caller)
{
	webrtc::MediaStreamTrackInterface * track = GetHandle<webrtc::MediaStreamTrackInterface>(env, caller);
	CHECK_HANDLEV(track, false);

	return static_cast<jboolean>(track->enabled());
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_MediaStreamTrack_setEnabled
(JNIEnv * env, jobject caller, jboolean enabled)
{
	webrtc::MediaStreamTrackInterface * track = GetHandle<webrtc::MediaStreamTrackInterface>(env, caller);
	CHECK_HANDLE(track);

	track->set_enabled(static_cast<bool>(enabled));
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_media_MediaStreamTrack_getState
(JNIEnv * env, jobject caller)
{
	webrtc::MediaStreamTrackInterface * track = GetHandle<webrtc::MediaStreamTrackInterface>(env, caller);
	CHECK_HANDLEV(track, nullptr);

	return jni::JavaEnums::toJava(env, track->state()).release();
}
