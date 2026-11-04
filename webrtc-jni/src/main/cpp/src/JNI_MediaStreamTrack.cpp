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
#include "AllocationFault.h"
#include "NativeHandle.h"
#include "JavaContext.h"
#include "JavaEnums.h"
#include "JavaError.h"
#include "JavaFactories.h"
#include "JavaString.h"
#include "JavaUtils.h"
#include "WebRTCContext.h"

#include "media/MediaStreamTrackObserver.h"
#include "media/MediaStreamTrackView.h"

#include "api/media_stream_interface.h"
#include "rtc_base/logging.h"
#include "rtc_base/thread.h"

#include <memory>
#include <vector>

namespace
{
	void AddListener(JNIEnv * env, webrtc::MediaStreamTrackInterface * track, jobject jListener, jni::MediaStreamTrackEvent event)
	{
		auto observer = std::make_shared<jni::MediaStreamTrackObserver>(env, jni::JavaGlobalRef<jobject>(env, jListener), track, event);

		track->RegisterObserver(observer.get());

		try {
			jni::AllocationFault::CheckListener();

			javaContext->addNativeRef(env, jni::JavaLocalRef<jobject>(env, jListener), observer, track, static_cast<int>(event));
		}
		catch (...) {
			// The track holds a bare pointer, and the observer goes with this
			// scope, so it has to leave the track first. No notification can
			// call it after that: unregistering runs on the track's thread,
			// behind one under way there, and a listener adding this one from
			// within a notification is on a list copied before it got added.
			observer->detach();
			track->UnregisterObserver(observer.get());

			throw;
		}
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_MediaStreamTrack_dispose
(JNIEnv * env, jobject caller)
{
	webrtc::MediaStreamTrackInterface * track = jni::TakeHandle<webrtc::MediaStreamTrackInterface>(env, caller);

	if (track == nullptr) {
		return;
	}

	// A view holds no reference to the track and does not own its listeners:
	// the track lives on with whatever owns it, so only this object detaches.
	if (jni::MediaStreamTrackView::isView(env, caller)) {
		return;
	}

	// Disposed from a listener of this track, or of another track the same
	// notification reaches: WebRTC goes on calling the observers of the track
	// from a copy of their list, and they use the track. Both are freed once
	// the notification is over.
	bool inCallback = jni::MediaStreamTrackObserver::InCallback();
	webrtc::Thread * callbackThread = inCallback ? webrtc::Thread::Current() : nullptr;

	// Listeners still registered are taken off the track before it goes. Their
	// entries are keyed by this track's address, which a later track can be
	// given, and an observer left registered would be freed with its entry
	// while the track (if something else still holds it) can still call it.
	auto observers = javaContext->removeNativeRefs<jni::MediaStreamTrackObserver>(track);

	for (const auto & observer : observers) {
		observer->detach();
		track->UnregisterObserver(observer.get());
	}

	jni::ReleaseHandle(env, track, [track, observers, inCallback, callbackThread](bool deferred) {
		// The observers go with the last copy of this.
		auto release = [track, observers, report = deferred || inCallback]() {
			if (track->Release() != webrtc::RefCountReleaseStatus::kDroppedLastRef) {
				jni::NativeHandle::ReportNotDeleted(report);
			}
		};

		if (callbackThread != nullptr) {
			// Also when a method of another thread held the track until now:
			// the notification may still be under way on the callback's thread.
			callbackThread->PostTask(std::move(release));
		}
		else if (inCallback && !deferred) {
			jni::MediaStreamTrackObserver::AfterNotification(std::move(release));
		}
		else {
			release();
		}
	});
}

JNIEXPORT jstring JNICALL Java_dev_onvoid_webrtc_media_MediaStreamTrack_getKind
(JNIEnv * env, jobject caller)
{
	try {
		jni::NativeLease<webrtc::MediaStreamTrackInterface> track(env, caller);
		CHECK_HANDLEV(track, nullptr);

		return jni::JavaString::toJava(env, track->kind()).release();
	}
	catch (...) {
		ThrowCxxJavaException(env);
		return nullptr;
	}
}

JNIEXPORT jstring JNICALL Java_dev_onvoid_webrtc_media_MediaStreamTrack_getId
(JNIEnv * env, jobject caller)
{
	try {
		jni::NativeLease<webrtc::MediaStreamTrackInterface> track(env, caller);
		CHECK_HANDLEV(track, nullptr);

		return jni::JavaString::toJava(env, track->id()).release();
	}
	catch (...) {
		ThrowCxxJavaException(env);
		return nullptr;
	}
}

JNIEXPORT jboolean JNICALL Java_dev_onvoid_webrtc_media_MediaStreamTrack_isEnabled
(JNIEnv * env, jobject caller)
{
	jni::NativeLease<webrtc::MediaStreamTrackInterface> track(env, caller);
	CHECK_HANDLEV(track, false);

	return static_cast<jboolean>(track->enabled());
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_MediaStreamTrack_setEnabled
(JNIEnv * env, jobject caller, jboolean enabled)
{
	jni::NativeLease<webrtc::MediaStreamTrackInterface> track(env, caller);
	CHECK_HANDLE(track);

	track->set_enabled(static_cast<bool>(enabled));
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_media_MediaStreamTrack_getState
(JNIEnv * env, jobject caller)
{
	try {
		jni::NativeLease<webrtc::MediaStreamTrackInterface> track(env, caller);
		CHECK_HANDLEV(track, nullptr);

		return jni::JavaEnums::toJava(env, track->state()).release();
	}
	catch (...) {
		ThrowCxxJavaException(env);
		return nullptr;
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_MediaStreamTrack_addEndedEventListener
(JNIEnv * env, jobject caller, jobject jListener)
{
	jni::NativeLease<webrtc::MediaStreamTrackInterface> track(env, caller);
	CHECK_HANDLE(track);

	try {
		AddListener(env, track, jListener, jni::MediaStreamTrackEvent::ended);
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_MediaStreamTrack_removeEndedEventListener
(JNIEnv * env, jobject caller, jobject jListener)
{
	jni::NativeLease<webrtc::MediaStreamTrackInterface> track(env, caller);
	CHECK_HANDLE(track);

	try {
		auto observerPtr = javaContext->removeNativeRef<jni::MediaStreamTrackObserver>(env, jni::JavaLocalRef<jobject>(env, jListener), track, static_cast<int>(jni::MediaStreamTrackEvent::ended));

		if (observerPtr) {
			observerPtr->detach();
			track->UnregisterObserver(observerPtr.get());

			// Removed in a callback, maybe its own: freed once the notification
			// is over.
			jni::MediaStreamTrackObserver::AfterNotification([observerPtr]() {});
		}
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_MediaStreamTrack_addMuteEventListener
(JNIEnv * env, jobject caller, jobject jListener)
{
	jni::NativeLease<webrtc::MediaStreamTrackInterface> track(env, caller);
	CHECK_HANDLE(track);

	try {
		AddListener(env, track, jListener, jni::MediaStreamTrackEvent::mute);
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_MediaStreamTrack_removeMuteEventListener
(JNIEnv * env, jobject caller, jobject jListener)
{
	jni::NativeLease<webrtc::MediaStreamTrackInterface> track(env, caller);
	CHECK_HANDLE(track);

	try {
		auto observerPtr = javaContext->removeNativeRef<jni::MediaStreamTrackObserver>(env, jni::JavaLocalRef<jobject>(env, jListener), track, static_cast<int>(jni::MediaStreamTrackEvent::mute));

		if (observerPtr) {
			observerPtr->detach();
			track->UnregisterObserver(observerPtr.get());

			// Removed in a callback, maybe its own: freed once the notification
			// is over.
			jni::MediaStreamTrackObserver::AfterNotification([observerPtr]() {});
		}
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}
