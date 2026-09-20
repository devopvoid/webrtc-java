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

#ifndef WEBRTC_JAVA_MEDIA_JAVA_PLAYER_OBSERVER_H_
#define WEBRTC_JAVA_MEDIA_JAVA_PLAYER_OBSERVER_H_

#include "media/MediaPlayerObserver.h"

#include <jni.h>

namespace ffmpeg
{
	// Passes a player's reports on to its Java MediaPlayer, which turns them
	// into calls on whatever listener the application set.
	//
	// The calls arrive on the player's own thread, which the JVM knows nothing
	// about, so each one attaches that thread, makes the call and detaches
	// again. Attaching is not free, but these are state changes and errors,
	// not frames: they happen a handful of times over a whole playback.
	class JavaPlayerObserver : public MediaPlayerObserver
	{
		public:
			// Keeps a global reference to the given Java MediaPlayer, so the
			// player may outlive the call that created it.
			JavaPlayerObserver(JNIEnv * env, jobject player);
			~JavaPlayerObserver() override;

			JavaPlayerObserver(const JavaPlayerObserver &) = delete;
			JavaPlayerObserver & operator=(const JavaPlayerObserver &) = delete;

			void OnStateChanged(int state) override;
			void OnEndOfStream() override;
			void OnError(const std::string & message) override;

		private:
			// Returns an environment for the calling thread, attaching it if
			// it is not known to the JVM. Sets attached when it did, which is
			// then the caller's to undo.
			JNIEnv * Attach(bool * attached);
			void Detach(bool attached);

			// A listener that throws must not be left to surface somewhere
			// unrelated later, so anything pending is reported and cleared.
			void ClearPendingException(JNIEnv * env);

			JavaVM * vm_ = nullptr;
			jobject player_ = nullptr;
			jmethodID on_state_changed_ = nullptr;
			jmethodID on_end_of_stream_ = nullptr;
			jmethodID on_error_ = nullptr;
	};
}

#endif
