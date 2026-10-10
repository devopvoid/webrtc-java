/*
 * Copyright 2026 Alex Andres
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

#include "media/MediaStreamTrackView.h"
#include "JavaClasses.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

#include <utility>

namespace jni
{
	JavaLocalRef<jobject> MediaStreamTrackView::create(JNIEnv * env, const webrtc::MediaStreamTrackInterface * track)
	{
		if (const webrtc::AudioTrackInterface * t = dynamic_cast<const webrtc::AudioTrackInterface *>(track)) {
			return markView(env, JavaFactories::create(env, t));
		}
		else if (const webrtc::VideoTrackInterface * t = dynamic_cast<const webrtc::VideoTrackInterface *>(track)) {
			return markView(env, JavaFactories::create(env, t));
		}

		return JavaLocalRef<jobject>(env, nullptr);
	}

	JavaLocalRef<jobject> MediaStreamTrackView::markView(JNIEnv * env, JavaLocalRef<jobject> && javaTrack)
	{
		const auto javaClass = JavaClasses::get<JavaMediaStreamTrackClass>(env);

		env->SetBooleanField(javaTrack, javaClass->view, JNI_TRUE);

		return std::move(javaTrack);
	}

	bool MediaStreamTrackView::isView(JNIEnv * env, jobject javaTrack)
	{
		const auto javaClass = JavaClasses::get<JavaMediaStreamTrackClass>(env);

		return env->GetBooleanField(javaTrack, javaClass->view) == JNI_TRUE;
	}

	MediaStreamTrackView::JavaMediaStreamTrackClass::JavaMediaStreamTrackClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG_MEDIA"MediaStreamTrack");

		view = GetFieldID(env, cls, "view", "Z");
	}
}
