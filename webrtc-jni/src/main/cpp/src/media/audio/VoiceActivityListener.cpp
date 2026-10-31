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

#include "media/audio/VoiceActivityListener.h"
#include "api/WebRTCUtils.h"
#include "JavaClasses.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	VoiceActivityListener::VoiceActivityListener(JNIEnv * env, const JavaGlobalRef<jobject> & listener,
		float threshold, int64_t hangoverNanos) :
		listener(listener),
		javaClass(JavaClasses::get<JavaVoiceActivityListenerClass>(env)),
		threshold(threshold),
		hangoverChunks(static_cast<int>(hangoverNanos / 10000000 + (hangoverNanos % 10000000 != 0))),
		quietChunks(0),
		speaking(false)
	{
	}

	void VoiceActivityListener::Update(float probability)
	{
		if (probability >= threshold) {
			quietChunks = 0;

			if (speaking) {
				return;
			}

			speaking = true;
		}
		else if (speaking && ++quietChunks > hangoverChunks) {
			quietChunks = 0;
			speaking = false;
		}
		else {
			return;
		}

		JNIEnv * env = AttachCurrentThread();

		if (env == nullptr) {
			return;
		}

		env->CallVoidMethod(listener, javaClass->onVoiceActivity, static_cast<jboolean>(speaking));

		ReportPendingException(env);
	}

	VoiceActivityListener::JavaVoiceActivityListenerClass::JavaVoiceActivityListenerClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG_AUDIO"VoiceActivityListener");

		onVoiceActivity = GetMethod(env, cls, "onVoiceActivity", "(Z)V");
	}
}
