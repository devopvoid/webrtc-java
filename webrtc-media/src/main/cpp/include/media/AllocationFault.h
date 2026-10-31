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

#ifndef WEBRTC_JAVA_MEDIA_ALLOCATION_FAULT_H_
#define WEBRTC_JAVA_MEDIA_ALLOCATION_FAULT_H_

namespace ffmpeg
{
	// Makes the copy of a Java string fail as an exhausted heap does, so that
	// the tests can see what an entry point does then: the C++ heap does not
	// run out on demand.
	//
	// Only the tests arm it, through JNI_AllocationFault. A copy that is not
	// armed pays a single atomic load.
	class AllocationFault
	{
		public:
			// Fails the next copy of a Java string, once.
			static void FailNextStringCopy();
			// Fails the next other allocation that is checked, once.
			static void FailNextAllocation();
			// Fails the next thread start that is checked, once, as one does
			// that the system has no memory for.
			static void FailNextThreadStart();
			// Fails an allocation of a recorder's writer thread, once, after
			// it has handled the given number of frames.
			static void FailWriterAllocation(int after_frames);
			// Forgets a fault that has not fired.
			static void Disarm();
			// Whether a fault is armed that has not fired.
			static bool IsArmed();
			// Throws std::bad_alloc if a fault is armed, and disarms it.
			static void CheckStringCopy();
			// The same for the other allocations.
			static void CheckAllocation();
			// Throws std::system_error if a fault is armed, and disarms it.
			static void CheckThreadStart();
			// Throws std::bad_alloc if a writer fault is armed and its frames
			// are counted down, and disarms it.
			static void CheckWriterAllocation();
	};
}

#endif
