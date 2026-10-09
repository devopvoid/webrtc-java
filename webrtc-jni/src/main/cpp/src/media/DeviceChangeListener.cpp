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

#include "rtc_base/logging.h"

#include <exception>

namespace jni
{
	DeviceChangeListener::DeviceChangeListener(JNIEnv * env, const JavaGlobalRef<jobject> & listener) :
		listener(listener),
		javaClass(JavaClasses::get<JavaDeviceChangeListenerClass>(env))
	{
	}

	void DeviceChangeListener::deviceConnected(avdev::DevicePtr device)
	{
		notify(device, javaClass->deviceConnected);
	}

	void DeviceChangeListener::deviceDisconnected(avdev::DevicePtr device)
	{
		notify(device, javaClass->deviceDisconnected);
	}

	void DeviceChangeListener::notify(const avdev::DevicePtr & device, jmethodID method)
	{
		JNIEnv * env = AttachCurrentThread();

		if (env == nullptr) {
			return;
		}

		// This runs on a thread the operating system owns (a CoreAudio queue, the MMDevice
		// notification thread, the PulseAudio main loop, ...), which has no handler for a C++
		// exception and never returns to Java. So nothing may be thrown from here, and what the
		// Java listener throws is reported and cleared: left pending, it would be there for the
		// next JNI call on this thread, which may be the next listener or the next event.
		try {
			JavaLocalRef<jobject> jdevice = nullptr;

			if (dynamic_cast<jni::avdev::AudioDevice *>(device.get())) {
				jdevice = AudioDevice::toJavaAudioDevice(env, device);
			}
			else if (dynamic_cast<jni::avdev::VideoDevice *>(device.get())) {
				const auto dev = dynamic_cast<jni::avdev::VideoDevice *>(device.get());
				jdevice = VideoDevice::toJavaVideoDevice(env, *dev);
			}

			if (jdevice) {
				env->CallVoidMethod(listener, method, jdevice.get());
			}
		}
		catch (const std::exception & e) {
			RTC_LOG(LS_ERROR) << "Device change notification failed: " << e.what();
		}
		catch (...) {
			RTC_LOG(LS_ERROR) << "Device change notification failed";
		}

		if (env->ExceptionCheck()) {
			env->ExceptionDescribe();
			env->ExceptionClear();
		}
	}

	DeviceChangeListener::JavaDeviceChangeListenerClass::JavaDeviceChangeListenerClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG_MEDIA"DeviceChangeListener");

		deviceConnected = GetMethod(env, cls, "deviceConnected", "(L" PKG_MEDIA "Device;)V");
		deviceDisconnected = GetMethod(env, cls, "deviceDisconnected", "(L" PKG_MEDIA "Device;)V");
	}
}