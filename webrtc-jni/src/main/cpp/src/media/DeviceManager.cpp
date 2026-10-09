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

#include "media/DeviceManager.h"

#include <vector>

namespace jni
{
	namespace avdev
	{
		void DeviceManager::attachHotplugListener(PDeviceHotplugListener listener)
		{
			std::lock_guard<std::mutex> lock(listenerMutex);

			hotplugListeners.push_back(listener);
		}

		void DeviceManager::detachHotplugListener(PDeviceHotplugListener listener)
		{
			std::lock_guard<std::mutex> lock(listenerMutex);

			hotplugListeners.remove_if([listener](std::weak_ptr<DeviceHotplugListener> p) {
				return !(p.owner_before(listener) || listener.owner_before(p));
			});
		}

		void DeviceManager::notifyDeviceConnected(DevicePtr device)
		{
			notifyListeners(device, DeviceEvent::Connected);
		}

		void DeviceManager::notifyDeviceDisconnected(DevicePtr device)
		{
			notifyListeners(device, DeviceEvent::Disconnected);
		}

		void DeviceManager::notifyListeners(DevicePtr device, const DeviceEvent event)
		{
			// Listeners are attached and detached on Java threads while the operating system
			// reports device changes on its own. The listeners are called on a copy, outside the
			// lock, so that one may detach itself (or another) from within its callback.
			std::vector<PDeviceHotplugListener> listeners;

			{
				std::lock_guard<std::mutex> lock(listenerMutex);

				for (auto i = hotplugListeners.begin(); i != hotplugListeners.end();) {
					PDeviceHotplugListener listener = (*i).lock();

					if (listener) {
						listeners.push_back(listener);
						++i;
					}
					else {
						i = hotplugListeners.erase(i);
					}
				}
			}

			for (const PDeviceHotplugListener & listener : listeners) {
				if (event == DeviceEvent::Connected) {
					listener->deviceConnected(device);
				}
				else if (event == DeviceEvent::Disconnected) {
					listener->deviceDisconnected(device);
				}
			}
		}
	}
}