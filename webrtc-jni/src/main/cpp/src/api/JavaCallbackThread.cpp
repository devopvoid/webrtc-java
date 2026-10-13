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

#include "api/JavaCallbackThread.h"
#include "JavaContext.h"

#include "rtc_base/logging.h"

#include <condition_variable>
#include <deque>
#include <mutex>
#include <thread>
#include <utility>

namespace jni
{
	namespace
	{
		struct Queue
		{
			std::mutex mutex;
			std::condition_variable condition;
			std::deque<absl::AnyInvocable<void(JNIEnv *) &&>> callbacks;
		};

		void Run(Queue * queue)
		{
			JavaVM * vm = javaContext->getVM();
			JNIEnv * env = nullptr;

			JavaVMAttachArgs args;
			args.version = JNI_VERSION_1_6;
			args.name = const_cast<char *>("WebRTC-Callbacks");
			args.group = nullptr;

			if (vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void **>(&env), &args) != JNI_OK) {
				env = nullptr;

				RTC_LOG(LS_ERROR) << "The Java callback thread cannot attach to the JVM, observers will not be called";
			}

			while (true) {
				absl::AnyInvocable<void(JNIEnv *) &&> callback;

				{
					std::unique_lock<std::mutex> lock(queue->mutex);

					queue->condition.wait(lock, [queue] {
						return !queue->callbacks.empty();
					});

					callback = std::move(queue->callbacks.front());
					queue->callbacks.pop_front();
				}

				if (env != nullptr) {
					try {
						std::move(callback)(env);
					}
					catch (...) {
						RTC_LOG(LS_ERROR) << "A Java callback failed with a native exception";
					}
				}
			}
		}

		Queue & GetQueue()
		{
			// Never destroyed: its thread waits on it for as long as the
			// process runs.
			static Queue * queue = [] {
				Queue * created = new Queue();

				std::thread(Run, created).detach();

				return created;
			}();

			return *queue;
		}
	}

	void JavaCallbackThread::Post(absl::AnyInvocable<void(JNIEnv *) &&> callback)
	{
		Queue & queue = GetQueue();

		{
			std::lock_guard<std::mutex> lock(queue.mutex);

			queue.callbacks.push_back(std::move(callback));
		}

		queue.condition.notify_one();
	}
}
