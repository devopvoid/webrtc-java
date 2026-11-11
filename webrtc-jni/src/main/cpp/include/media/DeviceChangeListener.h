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

#ifndef JNI_WEBRTC_MEDIA_DEVICE_CHANGE_LISTENER_H_
#define JNI_WEBRTC_MEDIA_DEVICE_CHANGE_LISTENER_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include "media/DeviceHotplugListener.h"

#include <atomic>
#include <jni.h>
#include <memory>

namespace jni
{

	// Calls a Java DeviceChangeListener on the device change thread of
	// JavaCallbackThread, not on the thread of the operating system that
	// reports the change.
	class DeviceChangeListener : public avdev::DeviceHotplugListener, public std::enable_shared_from_this<DeviceChangeListener>
	{
		public:
			explicit DeviceChangeListener(JNIEnv * env, const JavaGlobalRef<jobject> & listener);
			~DeviceChangeListener() = default;

			// DeviceHotplugListener implementation.
			void deviceConnected(avdev::DevicePtr device) override;
			void deviceDisconnected(avdev::DevicePtr device) override;

			// Drops the changes that are still queued for the Java listener,
			// once it has been removed.
			void detach();

		private:
			void notify(const avdev::DevicePtr & device, jmethodID method);
			void call(JNIEnv * env, const avdev::DevicePtr & device, jmethodID method);

		private:
			class JavaDeviceChangeListenerClass : public JavaClass
			{
				public:
					explicit JavaDeviceChangeListenerClass(JNIEnv * env);

					jmethodID deviceConnected;
					jmethodID deviceDisconnected;
			};

		private:
			JavaGlobalRef<jobject> listener;

			std::atomic<bool> detached;

			const std::shared_ptr<JavaDeviceChangeListenerClass> javaClass;
	};
}

#endif