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

#include "media/DeviceChangeListener.h"
#include "media/audio/AudioDevice.h"
#include "media/video/VideoDevice.h"
#include "JavaFactories.h"
#include "JavaString.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

namespace jni
{
	DeviceChangeListener::DeviceChangeListener(JNIEnv * env, const JavaGlobalRef<jobject> & listener) :
		listener(listener),
		javaClass(JavaClasses::get<JavaDeviceChangeListenerClass>(env))
	{
	}

	void DeviceChangeListener::deviceConnected(avdev::DevicePtr device)
	{
		JNIEnv * env = AttachCurrentThread();
		JavaLocalRef<jobject> jdevice = nullptr;

		if (dynamic_cast<jni::avdev::AudioDevice *>(device.get())) {
			jdevice = AudioDevice::toJavaAudioDevice(env, device);
		}
		else if (dynamic_cast<jni::avdev::VideoDevice *>(device.get())) {
			const auto dev = dynamic_cast<jni::avdev::VideoDevice*>(device.get());
			jdevice = VideoDevice::toJavaVideoDevice(env, *dev);
		}

		if (jdevice) {
			env->CallVoidMethod(listener, javaClass->deviceConnected, jdevice.get());
		}
	}

	void DeviceChangeListener::deviceDisconnected(avdev::DevicePtr device)
	{
		JNIEnv * env = AttachCurrentThread();
		JavaLocalRef<jobject> jdevice = nullptr;

		if (dynamic_cast<jni::avdev::AudioDevice *>(device.get())) {
			jdevice = AudioDevice::toJavaAudioDevice(env, device);
		}
		else if (dynamic_cast<jni::avdev::VideoDevice *>(device.get())) {
			const auto dev = dynamic_cast<jni::avdev::VideoDevice *>(device.get());
			jdevice = VideoDevice::toJavaVideoDevice(env, *dev);
		}

		if (jdevice) {
			env->CallVoidMethod(listener, javaClass->deviceDisconnected, jdevice.get());
		}
	}

	DeviceChangeListener::JavaDeviceChangeListenerClass::JavaDeviceChangeListenerClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG_MEDIA"DeviceChangeListener");

		deviceConnected = GetMethod(env, cls, "deviceConnected", "(L" PKG_MEDIA "Device;)V");
		deviceDisconnected = GetMethod(env, cls, "deviceDisconnected", "(L" PKG_MEDIA "Device;)V");
	}
}