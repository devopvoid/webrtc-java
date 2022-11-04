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
#include "JavaContext.h"
#include "JavaEnums.h"
#include "JavaError.h"
#include "JavaFactories.h"
#include "JavaString.h"
#include "WebRTCContext.h"

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

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_MediaStreamTrack_addEndedEventListener
(JNIEnv * env, jobject caller, jobject jListener)
{
	webrtc::MediaStreamTrackInterface * track = GetHandle<webrtc::MediaStreamTrackInterface>(env, caller);
	CHECK_HANDLE(track);

	jni::WebRTCContext * context = static_cast<jni::WebRTCContext *>(javaContext);

	try {
		jni::MediaStreamTrackObserver * observer = new jni::MediaStreamTrackObserver(env, jni::JavaGlobalRef<jobject>(env, jListener), track, jni::MediaStreamTrackEvent::ended);
		auto observerPtr = std::shared_ptr<jni::MediaStreamTrackObserver>(observer);

		track->RegisterObserver(observer);

		javaContext->addNativeRef(env, jni::JavaLocalRef<jobject>(env, jListener), observerPtr);
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_MediaStreamTrack_removeEndedEventListener
(JNIEnv * env, jobject caller, jobject jListener)
{
	webrtc::MediaStreamTrackInterface * track = GetHandle<webrtc::MediaStreamTrackInterface>(env, caller);
	CHECK_HANDLE(track);

	jni::WebRTCContext * context = static_cast<jni::WebRTCContext *>(javaContext);

	try {
		auto observerPtr = javaContext->removeNativeRef<jni::MediaStreamTrackObserver>(env, jni::JavaLocalRef<jobject>(env, jListener));

		if (observerPtr) {
			track->UnregisterObserver(observerPtr.get());
		}
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_MediaStreamTrack_addMuteEventListener
(JNIEnv * env, jobject caller, jobject jListener)
{
	webrtc::MediaStreamTrackInterface * track = GetHandle<webrtc::MediaStreamTrackInterface>(env, caller);
	CHECK_HANDLE(track);

	jni::WebRTCContext * context = static_cast<jni::WebRTCContext*>(javaContext);

	try {
		jni::MediaStreamTrackObserver * observer = new jni::MediaStreamTrackObserver(env, jni::JavaGlobalRef<jobject>(env, jListener), track, jni::MediaStreamTrackEvent::mute);
		auto observerPtr = std::shared_ptr<jni::MediaStreamTrackObserver>(observer);

		track->RegisterObserver(observer);

		javaContext->addNativeRef(env, jni::JavaLocalRef<jobject>(env, jListener), observerPtr);
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_MediaStreamTrack_removeMuteEventListener
(JNIEnv * env, jobject caller, jobject jListener)
{
	webrtc::MediaStreamTrackInterface * track = GetHandle<webrtc::MediaStreamTrackInterface>(env, caller);
	CHECK_HANDLE(track);

	jni::WebRTCContext * context = static_cast<jni::WebRTCContext *>(javaContext);

	try {
		auto observerPtr = javaContext->removeNativeRef<jni::MediaStreamTrackObserver>(env, jni::JavaLocalRef<jobject>(env, jListener));

		if (observerPtr) {
			track->UnregisterObserver(observerPtr.get());
		}
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}
