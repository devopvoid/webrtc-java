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

#include "media/Device.h"
#include "JavaClasses.h"
#include "JavaString.h"
#include "JNI_WebRTC.h"

namespace jni
{
	namespace avdev
	{
		Device::Device(std::string name, std::string descriptor) :
			name(name),
			descriptor(descriptor)
		{
		}

		bool Device::operator==(const Device & other)
		{
			return name == other.name && descriptor == other.descriptor;
		}

		bool Device::operator!=(const Device & other)
		{
			return !(*this == other);
		}

		bool Device::operator<(const Device & other)
		{
			return name < other.name;
		}

		std::string Device::getDescriptor() const
		{
			return descriptor;
		}

		std::string Device::getName() const
		{
			return name;
		}
	}

	namespace Device
	{
		JavaLocalRef<jobject> toJavaDevice(JNIEnv * env, avdev::DevicePtr device)
		{
			const auto javaClass = JavaClasses::get<JavaDeviceClass>(env);

			jobject obj = env->NewObject(javaClass->cls, javaClass->ctor,
				JavaString::toJava(env, device->getName()).get(),
				JavaString::toJava(env, device->getDescriptor()).get());

			return JavaLocalRef<jobject>(env, obj);
		}

		JavaDeviceClass::JavaDeviceClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG_AUDIO"Device");

			ctor = GetMethod(env, cls, "<init>", "(" STRING_SIG STRING_SIG ")V");

			name = GetFieldID(env, cls, "name", STRING_SIG);
			descriptor = GetFieldID(env, cls, "descriptor", STRING_SIG);
		}
	}
}