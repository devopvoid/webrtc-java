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

#ifndef JNI_WEBRTC_MEDIA_DEVICE_HOTPLUG_LISTENER_H_
#define JNI_WEBRTC_MEDIA_DEVICE_HOTPLUG_LISTENER_H_

#include "media/Device.h"

#include <memory>

namespace jni
{
	namespace avdev
	{
		class DeviceHotplugListener
		{
			public:
				virtual ~DeviceHotplugListener() {};

				virtual void deviceConnected(DevicePtr device) = 0;
				virtual void deviceDisconnected(DevicePtr device) = 0;
		};


		using PDeviceHotplugListener = std::shared_ptr<DeviceHotplugListener>;
	}
}

#endif