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

#ifndef JNI_WEBRTC_LEAK_DETECTION_H_
#define JNI_WEBRTC_LEAK_DETECTION_H_

#include <jni.h>

namespace jni
{
	// The native side of dev.onvoid.webrtc.internal.LeakDetector. While it is
	// off, which is the default, none of this calls into Java.
	namespace LeakDetection
	{
		// Tells the leak detector that the NativeObject no longer owns a native
		// object: it has been disposed or closed, or it never owned one, such
		// as a view of a track owned elsewhere. Keeps a pending exception.
		void Released(JNIEnv * env, jobject object) noexcept;
	}
}

#endif
