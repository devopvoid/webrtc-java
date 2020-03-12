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

#ifndef JNI_WEBRTC_MEDIA_DEVICE_LIST_H_
#define JNI_WEBRTC_MEDIA_DEVICE_LIST_H_

#include <algorithm>
#include <mutex>
#include <set>

namespace jni
{
	template <typename T>
	class DeviceList
	{
		public:
			DeviceList() = default;
			virtual ~DeviceList() = default;

			bool insertDevice(T device)
			{
				std::unique_lock<std::mutex> mlock(mutex);
				auto result = devicesSet.insert(device);
				mlock.unlock();

				return result.second;
			}

			bool removeDevice(T device)
			{
				std::unique_lock<std::mutex> mlock(mutex);
				size_t result = devicesSet.erase(device);
				mlock.unlock();

				return result;
			}

			T removeDevice(std::function<bool(T)> pred)
			{
				T device = nullptr;

				std::unique_lock<std::mutex> mlock(mutex);

				for (auto it = devicesSet.begin(), end = devicesSet.end(); it != end; ) {
					if (pred(*it)) {
						device = *it;
						devicesSet.erase(it);
						break;
					}
					else {
						++it;
					}
				}

				mlock.unlock();

				return device;
			}

			template <class Predicate>
			T findDevice(Predicate pred)
			{
				T device = nullptr;

				std::unique_lock<std::mutex> mlock(mutex);

				typename std::set<T>::iterator it = std::find_if(devicesSet.begin(), devicesSet.end(), pred);
				if (it != devicesSet.end()) {
					device = *it;
				}

				mlock.unlock();

				return device;
			}

			bool empty()
			{
				std::unique_lock<std::mutex> mlock(mutex);

				return devicesSet.empty();
			}

			std::set<T> devices()
			{
				std::unique_lock<std::mutex> mlock(mutex);

				return devicesSet;
			}

		private:
			std::set<T> devicesSet;
			std::mutex mutex;
	};
}

#endif