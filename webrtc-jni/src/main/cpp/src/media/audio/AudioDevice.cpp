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

#include "media/audio/AudioDevice.h"

#include "JavaClasses.h"
#include "JavaString.h"
#include "JNI_WebRTC.h"
#include "JavaEnums.h"

namespace jni
{
	namespace avdev
	{
		AudioDevice::AudioDevice(std::string name, std::string descriptor) :
			Device(name, descriptor),
			directionType(AudioDeviceDirectionType::adtUnknown)
		{
		}
	}

	namespace AudioDevice
	{
		JavaLocalRef<jobject> toJavaAudioDevice(JNIEnv * env, avdev::DevicePtr device)
		{
			const auto javaClass = JavaClasses::get<JavaAudioDeviceClass>(env);

			jobject obj = env->NewObject(javaClass->cls, javaClass->ctor,
				JavaString::toJava(env, device->getName()).get(),
				JavaString::toJava(env, device->getDescriptor()).get());

            auto deviceTransport = JavaEnums::toJava(env, device->getDeviceTransport());
            env->SetObjectField(obj, javaClass->deviceTransport, deviceTransport.get());

            auto deviceFormFactor = JavaEnums::toJava(env, device->getDeviceFormFactor());
            env->SetObjectField(obj, javaClass->deviceFormFactor, deviceFormFactor.get());

            auto audioDevice = dynamic_cast<jni::avdev::AudioDevice *>(device.get());
            auto directionType = JavaEnums::toJava(env, audioDevice->directionType);
            env->SetObjectField(obj, javaClass->directionType, directionType.get());

			return JavaLocalRef<jobject>(env, obj);
		}

		JavaAudioDeviceClass::JavaAudioDeviceClass(JNIEnv * env)
		{
			cls = FindClass(env, PKG_AUDIO"AudioDevice");

			ctor = GetMethod(env, cls, "<init>", "(" STRING_SIG STRING_SIG ")V");

			name = GetFieldID(env, cls, "name", STRING_SIG);
			descriptor = GetFieldID(env, cls, "descriptor", STRING_SIG);
			directionType = GetFieldID(env, cls, "directionType", "L" PKG_MEDIA "AudioDeviceDirectionType;");
			deviceTransport = GetFieldID(env, cls, "deviceTransport", "L" PKG_MEDIA "DeviceTransport;");
            deviceFormFactor = GetFieldID(env, cls, "deviceFormFactor", "L" PKG_MEDIA "DeviceFormFactor;");
		}
	}
}