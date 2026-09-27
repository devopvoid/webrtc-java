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

#ifndef JNI_WEBRTC_API_ENCODED_FRAME_WORKER_H_
#define JNI_WEBRTC_API_ENCODED_FRAME_WORKER_H_

#include "api/EncodedFrameRouter.h"

#include "api/frame_transformer_interface.h"

#include <jni.h>

#include <memory>

namespace jni
{
	// The thread a Java frame transform runs on.
	//
	// WebRTC hands frames to a transformer on the threads that carry media: a
	// receiver's on the network thread, a sender's on the encoder queue.
	// Running application code on those would stall the connection whenever
	// the transform takes a while, and deadlock it whenever the transform
	// calls back into the peer connection, which blocks on those very
	// threads. So frames are queued and transformed here instead, in order,
	// and handed back to WebRTC from here, which WebRTC allows from any
	// thread.
	//
	// The thread is attached to the JVM once, as a daemon so that it never
	// holds up the JVM's exit, and detaches itself when it ends.
	class EncodedFrameWorker
	{
		public:
			EncodedFrameWorker(JavaVM * vm, std::shared_ptr<EncodedFrameRouter> router);

			// Stops the thread without waiting for it. Frames still queued
			// are released rather than sent; the frame being transformed, if
			// any, finishes on its own.
			~EncodedFrameWorker();

			EncodedFrameWorker(const EncodedFrameWorker &) = delete;
			EncodedFrameWorker & operator=(const EncodedFrameWorker &) = delete;

			// Queues the frame for the transform. A transform that falls this
			// far behind has stopped keeping up for good, so the frame is
			// dropped rather than queued without bound.
			void Post(std::unique_ptr<webrtc::TransformableFrameInterface> frame);

		private:
			struct Queue;

			static void Run(JavaVM * vm, std::shared_ptr<Queue> queue,
				std::shared_ptr<EncodedFrameRouter> router);

		private:
			std::shared_ptr<Queue> queue;
	};
}

#endif
