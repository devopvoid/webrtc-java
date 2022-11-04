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
		JNIEnv * env = AttachCurrentThread();

		// Check state changes.

		if (eventType == MediaStreamTrackEvent::mute) {
			if (track->enabled() != trackEnabled) {
				// The condition "muted" is managed using the "enabled" property.
				env->CallVoidMethod(javaTrack, javaClass->onTrackMute, createJavaTrack(env).release(), !track->enabled());
			}
		}
		else if (eventType == MediaStreamTrackEvent::ended) {
			if (track->state() != trackState && track->state() == webrtc::MediaStreamTrackInterface::TrackState::kEnded) {
				env->CallVoidMethod(javaTrack, javaClass->onTrackEnd, createJavaTrack(env).release());
			}
		}

		// Save current state.
		trackEnabled = track->enabled();
		trackState = track->state();
	}

	JavaLocalRef<jobject> MediaStreamTrackObserver::createJavaTrack(JNIEnv * env)
	{
		if (const webrtc::AudioTrackInterface * t = dynamic_cast<const webrtc::AudioTrackInterface *>(track)) {
			return jni::JavaFactories::create(env, t);
		}
		else if (const webrtc::VideoTrackInterface * t = dynamic_cast<const webrtc::VideoTrackInterface *>(track)) {
			return jni::JavaFactories::create(env, t);
		}
		else {
			return jni::JavaLocalRef<jobject>(env, nullptr);
		}
	}

	MediaStreamTrackObserver::JavaMediaStreamTrackListenerClass::JavaMediaStreamTrackListenerClass(JNIEnv * env)
	{
		jclass clsEnded = FindClass(env, PKG_MEDIA"MediaStreamTrackEndedListener");
		jclass clsMute = FindClass(env, PKG_MEDIA"MediaStreamTrackMuteListener");

		onTrackEnd = GetMethod(env, clsEnded, "onTrackEnd", "(L" PKG_MEDIA "MediaStreamTrack;)V");
		onTrackMute = GetMethod(env, clsMute, "onTrackMute", "(L" PKG_MEDIA "MediaStreamTrack;Z)V");
	}
}