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

#ifndef JNI_WEBRTC_ALLOCATION_FAULT_H_
#define JNI_WEBRTC_ALLOCATION_FAULT_H_

namespace jni
{
	// Makes an allocation fail on purpose, so that the tests can see what the
	// code around it does when memory runs out, which it does not on demand.
	//
	// Only the tests arm it, through JNI_AllocationFault. A check that is not
	// armed pays a single flag load.
	class AllocationFault
	{
		public:
			// Fails the next registration of a track listener, once, after the
			// track got its observer and before the observer has an owner.
			static void FailNextListener();
			// Fails the next encoded_observer_add() of the extension API, once,
			// as an allocation for its handle does.
			static void FailNextEncodedObserver();
			// Fails the next handle lease, as inserting its map entry can.
			static void FailNextNativeLease();
			static void CheckNativeLease();
			// Forgets a fault that has not fired.
			static void Disarm();
			// Throws std::bad_alloc if a fault is armed, and disarms it.
			static void CheckListener();
			// The same for an encoded observer.
			static void CheckEncodedObserver();
	};
}

#endif
