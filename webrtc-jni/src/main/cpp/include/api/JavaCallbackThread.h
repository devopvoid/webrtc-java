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

#ifndef JNI_WEBRTC_API_JAVA_CALLBACK_THREAD_H_
#define JNI_WEBRTC_API_JAVA_CALLBACK_THREAD_H_

#include "absl/functional/any_invocable.h"

#include <jni.h>

namespace jni
{
	// The thread that runs the Java observers WebRTC notifies on its network
	// thread.
	//
	// The network thread is also WebRTC's worker thread, and many peer
	// connection calls block the signaling thread on the worker thread. An
	// observer making such a call on the network thread would wait for itself
	// and deadlock every connection of its factory, and a slow one would stall
	// their media. So these observers are queued and called here instead, one
	// at a time and in the order they were posted.
	//
	// The thread starts on first use and is attached to the JVM as a daemon,
	// so that it never holds up the JVM's exit.
	class JavaCallbackThread
	{
		public:
			static void Post(absl::AnyInvocable<void(JNIEnv *) &&> callback);
	};
}

#endif
