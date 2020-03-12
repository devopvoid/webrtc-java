/*
 * Copyright 2019 Alex Andres
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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_CAPTURE_CAPABILITY_H_
#define JNI_WEBRTC_MEDIA_VIDEO_CAPTURE_CAPABILITY_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include <jni.h>

#include "modules/video_capture/video_capture_defines.h"

namespace jni
{
	namespace avdev
	{
		class VideoCaptureCapability : public webrtc::VideoCaptureCapability
		{
			public:
				virtual bool operator<(const VideoCaptureCapability & other) const;
		};
	}

	namespace VideoCaptureCapability
	{
		class JavaVideoCaptureCapabilityClass : public JavaClass
		{
			public:
				explicit JavaVideoCaptureCapabilityClass(JNIEnv * env);

				jclass cls;
				jmethodID ctor;
		};

		JavaLocalRef<jobject> toJava(JNIEnv * env, const avdev::VideoCaptureCapability & capability);
	}
}

#endif