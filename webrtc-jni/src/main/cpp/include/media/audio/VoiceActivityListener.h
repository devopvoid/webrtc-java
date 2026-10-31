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

#ifndef JNI_WEBRTC_MEDIA_AUDIO_VOICE_ACTIVITY_LISTENER_H_
#define JNI_WEBRTC_MEDIA_AUDIO_VOICE_ACTIVITY_LISTENER_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include <jni.h>
#include <memory>

namespace jni
{
	// Decides from the voice probability of each 10 ms chunk when speech
	// begins and ends, and tells a Java VoiceActivityListener of each change,
	// and of nothing else. Speech begins with a chunk at or above the
	// threshold, and ends once the chunks stayed below it for longer than the
	// hangover.
	class VoiceActivityListener
	{
		public:
			VoiceActivityListener(JNIEnv * env, const JavaGlobalRef<jobject> & listener, float threshold,
				int hangoverMs);
			~VoiceActivityListener() = default;

			// Called on the thread that delivers the audio.
			void Update(float probability);

		private:
			class JavaVoiceActivityListenerClass : public JavaClass
			{
				public:
					explicit JavaVoiceActivityListenerClass(JNIEnv * env);

					jmethodID onVoiceActivity;
			};

		private:
			JavaGlobalRef<jobject> listener;

			const std::shared_ptr<JavaVoiceActivityListenerClass> javaClass;

			const float threshold;

			// The hangover in chunks, rounded up.
			const int hangoverChunks;

			// The chunks below the threshold since speech was last detected.
			int quietChunks;

			bool speaking;
	};
}

#endif
