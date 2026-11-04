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

#ifndef JNI_WEBRTC_API_OBSERVER_SLOT_H_
#define JNI_WEBRTC_API_OBSERVER_SLOT_H_

#include "JavaUtils.h"

#include <jni.h>

#include <functional>
#include <memory>
#include <mutex>
#include <vector>

namespace jni
{
	// Guards the creation and the taking of observer slots, never held while
	// WebRTC is called.
	std::mutex & ObserverSlotMutex();

	// The native observer a Java object registers on its WebRTC object, which
	// has room for one, kept in a long field of that Java object.
	//
	// Registering and removing may happen on several threads at once, and
	// while the object is disposed. WebRTC is told by one thread at a time:
	// a thread that finds another one telling WebRTC leaves its observer to
	// that thread and returns, so no thread waits for another, which may be
	// waiting for a WebRTC thread that runs a callback of the observer. An
	// observer is freed once WebRTC has been given a newer one, and never
	// twice.
	//
	// dispose() takes the slot right after the object's handle, and frees it
	// in the handle's release. That release runs once no native method of the
	// object is running any more, the ones that register included, and none
	// can start, so it is the last to tell WebRTC and finds the observer that
	// WebRTC has. A registration that comes too late to get a slot frees its
	// observer without telling WebRTC.
	template <typename T>
	class ObserverSlot
	{
		public:
			// Tells WebRTC about the observer that is to be registered now, or
			// to be removed when target is null. previous is the one it was
			// told about last through this slot, or null.
			using Apply = std::function<void(T * previous, T * target)>;

			~ObserverSlot()
			{
				delete desired;

				for (T * observer : retired) {
					delete observer;
				}
			}

			// Registers the observer through the Java object, or removes the
			// one registered through it when observer is null, and takes
			// ownership of it.
			static void Set(JNIEnv * env, jobject object, const char * field, T * observer, const Apply & apply)
			{
				std::unique_ptr<T> owned(observer);
				ObserverSlot * slot = Get(env, object, field);

				if (slot == nullptr) {
					// Disposed meanwhile.
					return;
				}

				std::vector<T *> unused;
				std::unique_lock<std::mutex> lock(slot->mutex);

				if (slot->desired != nullptr) {
					slot->retired.push_back(slot->desired);
				}

				slot->desired = owned.release();

				if (slot->busy) {
					// The thread telling WebRTC goes on with this observer.
					return;
				}

				slot->busy = true;

				while (true) {
					T * target = slot->desired;
					T * previous = slot->applied_;

					lock.unlock();

					try {
						apply(previous, target);
					}
					catch (...) {
						lock.lock();
						slot->busy = false;
						throw;
					}

					lock.lock();

					slot->applied_ = target;

					if (slot->desired == target) {
						// WebRTC has the latest; it has let go of all the others.
						slot->busy = false;
						unused.swap(slot->retired);
						break;
					}
				}

				lock.unlock();

				for (T * unusedObserver : unused) {
					delete unusedObserver;
				}
			}

			// Takes the slot of a Java object whose handle has been taken, for
			// the handle's release to free. No slot is made for it afterwards.
			static std::shared_ptr<ObserverSlot> Take(JNIEnv * env, jobject object, const char * field)
			{
				std::lock_guard<std::mutex> lock(ObserverSlotMutex());

				ObserverSlot * slot = GetHandle<ObserverSlot>(env, object, field);

				if (slot != nullptr) {
					SetHandle<std::nullptr_t>(env, object, field, nullptr);
				}

				return std::shared_ptr<ObserverSlot>(slot);
			}

			// The observer WebRTC was told about last, or null. Only for the
			// release of a disposed object, when nothing registers any more.
			T * applied() const
			{
				return applied_;
			}

		private:
			ObserverSlot() = default;

			// The slot of the Java object, made on first use, or null once its
			// handle has been taken.
			static ObserverSlot * Get(JNIEnv * env, jobject object, const char * field)
			{
				std::lock_guard<std::mutex> lock(ObserverSlotMutex());

				ObserverSlot * slot = GetHandle<ObserverSlot>(env, object, field);

				if (slot != nullptr) {
					return slot;
				}

				// Read under the lock that Take() holds: a dispose that took
				// the handle before takes this slot too, or finds none.
				if (GetHandle<void>(env, object) == nullptr) {
					return nullptr;
				}

				slot = new ObserverSlot();

				SetHandle(env, object, field, slot);

				return slot;
			}

		private:
			std::mutex mutex;
			// The observer to be registered last, owned.
			T * desired = nullptr;
			// What WebRTC was told about last, desired or one of retired.
			T * applied_ = nullptr;
			// Replaced observers WebRTC may still have, owned.
			std::vector<T *> retired;
			// Whether a thread is telling WebRTC.
			bool busy = false;
	};
}

#endif
