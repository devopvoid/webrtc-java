/*
 * Copyright 2022 Alex Andres
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

#include "media/MediaStreamTrackObserver.h"
#include "media/MediaStreamTrackView.h"
#include "api/WebRTCUtils.h"
#include "JavaFactories.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

#include "rtc_base/thread.h"

#include <utility>
#include <vector>

namespace jni
{
	namespace
	{
		// The callbacks this thread is in.
		thread_local int callbackDepth = 0;

		// Work that waits for the end of the outermost callback, on a thread
		// that is no WebRTC thread, which could run it after its task.
		thread_local std::vector<std::function<void()>> afterCallbacks;
	}

	MediaStreamTrackObserver::MediaStreamTrackObserver(JNIEnv * env, const JavaGlobalRef<jobject> & javaTrack, const webrtc::MediaStreamTrackInterface * track, const MediaStreamTrackEvent & eventType) :
		javaTrack(javaTrack),
		track(track),
		eventType(eventType),
		javaClass(JavaClasses::get<JavaMediaStreamTrackListenerClass>(env))
	{
		trackEnabled = track->enabled();
		trackState = track->state();
	}

	void MediaStreamTrackObserver::OnChanged()
	{
		// Removed, or its track disposed, in an earlier callback of the
		// notification under way, which WebRTC goes on with from a copy of its
		// list of observers.
		if (detached) {
			return;
		}

		JNIEnv * env = AttachCurrentThread();

		if (env == nullptr) {
			return;
		}

		struct CallbackScope
		{
			CallbackScope()
			{
				callbackDepth++;
			}

			~CallbackScope()
			{
				if (--callbackDepth > 0) {
					return;
				}

				std::vector<std::function<void()>> work;
				work.swap(afterCallbacks);

				for (auto & run : work) {
					run();
				}
			}
		};

		CallbackScope scope;

		// Check state changes.

		try {
			if (eventType == MediaStreamTrackEvent::mute) {
				if (track->enabled() != trackEnabled) {
					// The condition "muted" is managed using the "enabled" property.
					JavaLocalRef<jobject> jTrack = createJavaTrack(env);
					env->CallVoidMethod(javaTrack, javaClass->onTrackMute, jTrack.get(), !track->enabled());
				}
			}
			else if (eventType == MediaStreamTrackEvent::ended) {
				if (track->state() != trackState && track->state() == webrtc::MediaStreamTrackInterface::TrackState::kEnded) {
					JavaLocalRef<jobject> jTrack = createJavaTrack(env);
					env->CallVoidMethod(javaTrack, javaClass->onTrackEnd, jTrack.get());
				}
			}
		}
		catch (...) {
			ThrowCxxJavaException(env);
		}

		ReportPendingException(env);

		// Save current state.
		trackEnabled = track->enabled();
		trackState = track->state();
	}

	void MediaStreamTrackObserver::detach()
	{
		detached = true;
	}

	bool MediaStreamTrackObserver::InCallback()
	{
		return callbackDepth > 0;
	}

	void MediaStreamTrackObserver::AfterNotification(std::function<void()> work)
	{
		if (callbackDepth == 0) {
			work();
			return;
		}

		if (webrtc::Thread * thread = webrtc::Thread::Current()) {
			thread->PostTask(std::move(work));
		}
		else {
			afterCallbacks.push_back(std::move(work));
		}
	}

	JavaLocalRef<jobject> MediaStreamTrackObserver::createJavaTrack(JNIEnv * env)
	{
		return MediaStreamTrackView::create(env, track);
	}

	MediaStreamTrackObserver::JavaMediaStreamTrackListenerClass::JavaMediaStreamTrackListenerClass(JNIEnv * env)
	{
		jclass clsEnded = FindClass(env, PKG_MEDIA"MediaStreamTrackEndedListener");
		jclass clsMute = FindClass(env, PKG_MEDIA"MediaStreamTrackMuteListener");

		onTrackEnd = GetMethod(env, clsEnded, "onTrackEnd", "(L" PKG_MEDIA "MediaStreamTrack;)V");
		onTrackMute = GetMethod(env, clsMute, "onTrackMute", "(L" PKG_MEDIA "MediaStreamTrack;Z)V");
	}
}
