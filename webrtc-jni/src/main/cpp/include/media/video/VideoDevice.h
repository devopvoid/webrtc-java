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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_DEVICE_H_
#define JNI_WEBRTC_MEDIA_VIDEO_DEVICE_H_

#include "media/Device.h"
#include "JavaClass.h"
#include "JavaRef.h"

#include <jni.h>

namespace jni
{
	namespace avdev
	{
		class VideoDevice : public Device
		{ 
			public:
				VideoDevice(std::string name, std::string descriptor);
				virtual ~VideoDevice() {};
		};


		using VideoDevicePtr = std::shared_ptr<VideoDevice>;
	}

	namespace VideoDevice
	{
		class JavaVideoDeviceClass : public JavaClass
		{
			public:
				explicit JavaVideoDeviceClass(JNIEnv * env);

				jclass cls;
				jmethodID ctor;
				jfieldID name;
				jfieldID descriptor;
		};

		JavaLocalRef<jobject> toJavaVideoDevice(JNIEnv * env, const avdev::VideoDevice & device);
		avdev::VideoDevice toNativeVideoDevice(JNIEnv * env, const JavaRef<jobject> & javaType);
	}
}

#endif