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

#include "api/EncodedFrameWorker.h"
#include "api/RTCEncodedFrameTransformer.h"

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
		// A few seconds of video and audio together. A transform this far
		// behind is not going to catch up.
		constexpr size_t kMaxQueuedFrames = 256;
	}

	struct EncodedFrameWorker::Queue
	{
		std::mutex mutex;
		std::condition_variable condition;
		std::deque<std::unique_ptr<webrtc::TransformableFrameInterface>> frames;
		bool stopped = false;
		bool reportedOverflow = false;
	};

	EncodedFrameWorker::EncodedFrameWorker(JavaVM * vm, std::shared_ptr<EncodedFrameRouter> router) :
		queue(std::make_shared<Queue>())
	{
		std::thread(&EncodedFrameWorker::Run, vm, queue, std::move(router)).detach();
	}

	EncodedFrameWorker::~EncodedFrameWorker()
	{
		std::deque<std::unique_ptr<webrtc::TransformableFrameInterface>> dropped;

		{
			std::lock_guard<std::mutex> lock(queue->mutex);

			queue->stopped = true;
			dropped.swap(queue->frames);
		}

		queue->condition.notify_one();

		// The queued frames are released here, outside the lock.
	}

	void EncodedFrameWorker::Post(std::unique_ptr<webrtc::TransformableFrameInterface> frame)
	{
		{
			std::lock_guard<std::mutex> lock(queue->mutex);

			if (queue->stopped) {
				return;
			}

			if (queue->frames.size() >= kMaxQueuedFrames) {
				if (!queue->reportedOverflow) {
					queue->reportedOverflow = true;

					RTC_LOG(LS_WARNING) << "Encoded frame transform does not keep up, dropping frames";
				}

				return;
			}

			queue->frames.push_back(std::move(frame));
		}

		queue->condition.notify_one();
	}

	void EncodedFrameWorker::Run(JavaVM * vm, std::shared_ptr<Queue> queue,
		std::shared_ptr<EncodedFrameRouter> router)
	{
		JNIEnv * env = nullptr;

		JavaVMAttachArgs args;
		args.version = JNI_VERSION_1_6;
		args.name = const_cast<char *>("EncodedFrameTransform");
		args.group = nullptr;

		if (vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void **>(&env), &args) != JNI_OK) {
			env = nullptr;

			RTC_LOG(LS_ERROR) << "Encoded frame transform cannot attach its thread to the JVM";
		}

		while (true) {
			std::unique_ptr<webrtc::TransformableFrameInterface> frame;

			{
				std::unique_lock<std::mutex> lock(queue->mutex);

				queue->condition.wait(lock, [&queue] {
					return queue->stopped || !queue->frames.empty();
				});

				if (queue->stopped) {
					break;
				}

				frame = std::move(queue->frames.front());
				queue->frames.pop_front();
			}

			std::shared_ptr<RTCEncodedFrameTransformer> transformer = router->GetTransformer();

			if (transformer) {
				// A thread that could not attach cannot run the transform, and
				// the frame must then not go out untransformed.
				if (env == nullptr || !transformer->Transform(env, *frame, router->GetMediaType())) {
					continue;
				}

				// Released while still attached: it may be the last reference
				// to a transform that was replaced in the meantime.
				transformer.reset();
			}

			if (!router->IsSender()) {
				router->Observe(*frame);
			}

			router->Forward(std::move(frame));
		}

		// Everything that may hold a Java reference goes before the thread
		// leaves the JVM.
		router.reset();

		if (env != nullptr) {
			vm->DetachCurrentThread();
		}
	}
}
