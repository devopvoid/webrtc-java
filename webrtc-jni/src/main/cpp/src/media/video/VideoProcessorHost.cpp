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

#include "media/video/VideoProcessorHost.h"
#include "api/VideoFrame.h"
#include "api/WebRTCUtils.h"
#include "JavaClasses.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

#include "api/video/i420_buffer.h"

#include <utility>

namespace jni
{
	void VideoProcessorHost::SetVideoProcessor(JNIEnv * env, jobject processor)
	{
		std::shared_ptr<JavaGlobalRef<jobject>> next;

		if (processor != nullptr) {
			next = std::make_shared<JavaGlobalRef<jobject>>(env, processor);
		}

		std::shared_ptr<JavaGlobalRef<jobject>> previous;

		{
			std::lock_guard<std::mutex> lock(mutex);

			previous = std::exchange(this->processor, std::move(next));
		}

		// The previous processor is released here, outside the lock, unless a
		// frame is still being processed by it.
	}

	bool VideoProcessorHost::ProcessFrame(const webrtc::VideoFrame & frame)
	{
		std::shared_ptr<JavaGlobalRef<jobject>> current;

		{
			std::lock_guard<std::mutex> lock(mutex);

			current = processor;
		}

		if (!current) {
			return false;
		}

		JNIEnv * env = AttachCurrentThread();

		if (env == nullptr) {
			return true;
		}

		try {
			const auto processorClass = JavaClasses::get<JavaVideoProcessorClass>(env);
			const auto frameClass = JavaClasses::get<JavaVideoFrameClass>(env);

			webrtc::scoped_refptr<webrtc::I420BufferInterface> buffer = frame.video_frame_buffer()->ToI420();

			if (buffer != nullptr) {
				// The Java frame holds a reference of its own, which is dropped
				// once the processor returns. A processor that keeps the frame
				// retains it.
				buffer->AddRef();

				struct Reference
				{
					webrtc::I420BufferInterface * buffer;

					~Reference()
					{
						buffer->Release();
					}
				} reference{ buffer.get() };

				JavaLocalRef<jobject> jBuffer = I420Buffer::toJava(env, buffer);
				JavaLocalRef<jobject> jFrame(env, env->NewObject(frameClass->cls, frameClass->ctor, jBuffer.get(),
					static_cast<jint>(frame.rotation()), static_cast<jlong>(frame.timestamp_us() * 1000)));

				if (jFrame.get() != nullptr) {
					env->CallVoidMethod(current->get(), processorClass->onFrameCaptured, jFrame.get());
				}
			}
		}
		catch (...) {
			ThrowCxxJavaException(env);
		}

		// The capture thread belongs to WebRTC or the capture module.
		ReportPendingException(env);

		return true;
	}

	VideoProcessorHost::JavaVideoProcessorClass::JavaVideoProcessorClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG_VIDEO"VideoProcessor");

		onFrameCaptured = GetMethod(env, cls, "onFrameCaptured", "(L" PKG_VIDEO "VideoFrame;)V");
	}
}
