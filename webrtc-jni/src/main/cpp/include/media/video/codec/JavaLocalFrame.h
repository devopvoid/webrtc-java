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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_JAVA_LOCAL_FRAME_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_JAVA_LOCAL_FRAME_H_

#include <jni.h>

namespace jni
{
	// Scopes the local references made on a thread WebRTC owns. Such a
	// thread stays attached to the JVM for as long as it runs, so without a
	// frame of their own the references made for one call would pile up
	// behind those of the next.
	//
	// A frame that cannot be pushed reports the JVM's exception as a codec
	// exception, and the call it was meant for gives up rather than make
	// references that nothing would free soon: see pushed().
	class JavaLocalFrame
	{
		public:
			// The context names the call in the log.
			JavaLocalFrame(JNIEnv * env, jint capacity, const char * context);
			~JavaLocalFrame();

			JavaLocalFrame(const JavaLocalFrame &) = delete;
			JavaLocalFrame & operator=(const JavaLocalFrame &) = delete;

			// Whether the frame is in place, which a call checks before it
			// makes any local reference.
			bool pushed() const;

		private:
			JNIEnv * env;
			const bool isPushed;
	};
}

#endif
