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
#include "JavaFactories.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	MediaStreamTrackObserver::MediaStreamTrackObserver(JNIEnv * env, const JavaGlobalRef<jobject> & javaTrack, const webrtc::MediaStreamTrackInterface * track) :
		javaTrack(javaTrack),
		track(track),
		javaClass(JavaClasses::get<JavaMediaStreamTrackClass>(env))
	{
		trackEnabled = track->enabled();
		trackState = track->state();
	}

	void MediaStreamTrackObserver::OnChanged()
	{
		JNIEnv * env = AttachCurrentThread();

		// Check state changes.

		if (track->enabled() != trackEnabled) {
			// The condition "muted" is managed using the "enabled" property.
			env->CallVoidMethod(javaTrack, javaClass->onMuteState, !track->enabled());
		}

		if (track->state() != trackState && track->state() == webrtc::MediaStreamTrackInterface::TrackState::kEnded) {
			env->CallVoidMethod(javaTrack, javaClass->onEnded);
		}

		// Save current state.
		trackEnabled = track->enabled();
		trackState = track->state();
	}

	MediaStreamTrackObserver::JavaMediaStreamTrackClass::JavaMediaStreamTrackClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG_MEDIA"MediaStreamTrack");

		onEnded = GetMethod(env, cls, "onEnded", "()V");
		onMuteState = GetMethod(env, cls, "onMuteState", "(Z)V");
	}
}