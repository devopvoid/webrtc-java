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

#ifndef JNI_WEBRTC_API_REGISTERED_OBSERVERS_H_
#define JNI_WEBRTC_API_REGISTERED_OBSERVERS_H_

namespace jni
{
	// Which native observer is registered on a WebRTC object that has room for
	// one, such as a sender, a receiver or a DTLS transport.
	//
	// Many Java objects may stand for the same native object, and each owns
	// the observer registered through it. Registering through one replaces the
	// observer of another, which then must not unregister the one that
	// replaced it when it is disposed. The Java objects learn here whether the
	// observer they own is still the registered one.
	//
	// The registry is told before WebRTC is, and its lock is never held while
	// WebRTC is called: some observers are called on the thread that runs the
	// registration, and their Java code may register again.
	namespace RegisteredObservers
	{
		// Records the observer as the one registered on the owner, in place of
		// any other.
		void Set(const void * owner, const void * observer);

		// Forgets the observer if it is the one registered on the owner, and
		// returns whether it was. Only then is it to be unregistered.
		bool Remove(const void * owner, const void * observer);
	}
}

#endif
