/*
 * Copyright 2026 Alex Andres
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

#include "api/RegisteredObservers.h"

#include <mutex>
#include <unordered_map>

namespace jni
{
	namespace
	{
		using RegistryMap = std::unordered_map<const void *, const void *>;

		// Never destroyed: a Java object may be disposed while the process
		// shuts down, after static destructors have run.
		std::mutex & RegistryMutex()
		{
			static std::mutex * mutex = new std::mutex();
			return *mutex;
		}

		// An entry stays only as long as the Java object that registered the
		// observer, which holds a reference on the owner, so an owner's address
		// is not reused while its entry is here.
		RegistryMap & Registry()
		{
			static RegistryMap * registry = new RegistryMap();
			return *registry;
		}
	}

	namespace RegisteredObservers
	{
		void Set(const void * owner, const void * observer)
		{
			std::lock_guard<std::mutex> lock(RegistryMutex());

			Registry()[owner] = observer;
		}

		bool Remove(const void * owner, const void * observer)
		{
			if (observer == nullptr) {
				return false;
			}

			std::lock_guard<std::mutex> lock(RegistryMutex());

			auto found = Registry().find(owner);

			if (found == Registry().end() || found->second != observer) {
				return false;
			}

			Registry().erase(found);

			return true;
		}
	}
}
