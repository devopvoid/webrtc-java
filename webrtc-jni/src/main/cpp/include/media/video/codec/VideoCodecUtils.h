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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CODEC_VIDEO_CODEC_UTILS_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CODEC_VIDEO_CODEC_UTILS_H_

#include "JavaClass.h"

#include <jni.h>

#include <cstdint>

namespace jni
{
	// Logs and clears a pending Java exception, which is how the codec
	// wrappers deal with whatever a Java codec or factory throws: nothing
	// may throw into WebRTC, and no JNI call may be made with an exception
	// pending. Returns whether there was one.
	bool ClearCodecException(JNIEnv * env, const char * context);

	// Converts a Java VideoCodecStatus to a WEBRTC_VIDEO_CODEC_* code. A
	// pending exception, which it clears, and a null status are an error.
	int32_t ToNativeCodecStatus(JNIEnv * env, jobject status, const char * context);

	class JavaVideoCodecStatusClass : public JavaClass
	{
		public:
			explicit JavaVideoCodecStatusClass(JNIEnv * env);

			jclass cls;
			jmethodID getNumber;
	};
}

#endif
