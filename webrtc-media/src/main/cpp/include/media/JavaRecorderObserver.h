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

#ifndef WEBRTC_JAVA_MEDIA_JAVA_RECORDER_OBSERVER_H_
#define WEBRTC_JAVA_MEDIA_JAVA_RECORDER_OBSERVER_H_

#include "media/MediaRecorderObserver.h"

#include <jni.h>

namespace ffmpeg
{
	// Passes a recorder's reports on to its Java MediaRecorder.
	//
	// The Java side does no more than queue each report for a thread of its
	// own, so these calls return at once and the writer thread never waits
	// for the application, nor for WebRTC when a key frame is asked for.
	//
	// The writer thread is attached to the JVM for each call and detached
	// after it: reports are rare, a handful per recording plus at most one
	// key frame request per second and track.
	class JavaRecorderObserver : public MediaRecorderObserver
	{
		public:
			// Keeps a global reference to the given Java MediaRecorder.
			JavaRecorderObserver(JNIEnv * env, jobject recorder);
			~JavaRecorderObserver() override;

			JavaRecorderObserver(const JavaRecorderObserver &) = delete;
			JavaRecorderObserver & operator=(const JavaRecorderObserver &) = delete;

			void OnStarted() override;
			void OnWarning(const std::string & message) override;
			void OnError(const std::string & message) override;
			void OnKeyFrameNeeded(int track) override;

		private:
			// Calls a void Java method taking one String.
			void CallWithMessage(jmethodID method, const std::string & message);

			// Returns an environment for the calling thread, attaching it if
			// it is not known to the JVM. Sets attached when it did, which is
			// then the caller's to undo.
			JNIEnv * Attach(bool * attached);
			void Detach(bool attached);

			// A thread already carrying an exception must not call Java.
			static bool CanCallJava(JNIEnv * env);

			// Nothing called here may leave an exception behind.
			static void ClearPendingException(JNIEnv * env);

			JavaVM * vm_ = nullptr;
			jobject recorder_ = nullptr;
			jmethodID on_started_ = nullptr;
			jmethodID on_warning_ = nullptr;
			jmethodID on_error_ = nullptr;
			jmethodID on_key_frame_needed_ = nullptr;
	};
}

#endif
