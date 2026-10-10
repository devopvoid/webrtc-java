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

#ifndef JNI_WEBRTC_MEDIA_VIDEO_VIDEO_PROCESSOR_HOST_H_
#define JNI_WEBRTC_MEDIA_VIDEO_VIDEO_PROCESSOR_HOST_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include "api/video/video_frame.h"

#include <jni.h>
#include <memory>
#include <mutex>

namespace jni
{
	// A video source that can run its frames through a Java VideoProcessor.
	// The source calls ProcessFrame with each frame it would otherwise
	// deliver; the processor hands the frames to send back through the Java
	// sink, which ends up in DeliverProcessedFrame.
	class VideoProcessorHost
	{
		public:
			virtual ~VideoProcessorHost() = default;

			// Sets the Java processor, or removes it when null. A frame that is
			// being processed meanwhile still reaches the previous processor.
			void SetVideoProcessor(JNIEnv * env, jobject processor);

			// Hands the frame to the processor. Returns false when there is
			// none, so that the source delivers the frame itself. Never throws.
			bool ProcessFrame(const webrtc::VideoFrame & frame);

			// Delivers a frame the processor produced to the source's sinks.
			virtual void DeliverProcessedFrame(const webrtc::VideoFrame & frame) = 0;

		private:
			class JavaVideoProcessorClass : public JavaClass
			{
				public:
					explicit JavaVideoProcessorClass(JNIEnv * env);

					jmethodID onFrameCaptured;
			};

			std::mutex mutex;
			std::shared_ptr<JavaGlobalRef<jobject>> processor;
	};
}

#endif
