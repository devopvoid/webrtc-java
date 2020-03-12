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

#ifndef JNI_WEBRTC_MEDIA_DEVICE_H_
#define JNI_WEBRTC_MEDIA_DEVICE_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include <jni.h>
#include <string>
#include <memory>

namespace jni
{
	namespace avdev
	{
		class Device
		{
			public:
				virtual ~Device() {};

				virtual bool operator==(const Device & other);
				virtual bool operator!=(const Device & other);
				virtual bool operator<(const Device & other);

				std::string getName() const;
				std::string getDescriptor() const;

			protected:
				Device(std::string name, std::string descriptor);

			private:
				const std::string name;
				const std::string descriptor;
		};


		using DevicePtr = std::shared_ptr<Device>;
	}

	namespace Device
	{
		class JavaDeviceClass : public JavaClass
		{
			public:
				explicit JavaDeviceClass(JNIEnv * env);

				jclass cls;
				jmethodID ctor;
				jfieldID name;
				jfieldID descriptor;
		};

		JavaLocalRef<jobject> toJavaDevice(JNIEnv * env, avdev::DevicePtr device);
	}
}

#endif