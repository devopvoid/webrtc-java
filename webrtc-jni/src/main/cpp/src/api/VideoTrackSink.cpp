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

#include "api/VideoTrackSink.h"
#include "api/WebRTCUtils.h"
#include "JavaClasses.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

#include "api/video/i420_buffer.h"
#include "rtc_base/time_utils.h"

namespace jni
{
	VideoTrackSink::VideoTrackSink(JNIEnv * env, const JavaGlobalRef<jobject> & sink, bool shared) :
		sink(sink),
		shared(shared),
		javaClass(JavaClasses::get<JavaVideoTrackSinkClass>(env)),
		javaFrameClass(JavaClasses::get<JavaVideoFrameClass>(env)),
		javaBufferClass(JavaClasses::get<JavaNativeI420BufferClass>(env))
	{
	}

	void VideoTrackSink::OnFrame(const webrtc::VideoFrame & frame)
	{
		JNIEnv * env = AttachCurrentThread();

		if (env == nullptr) {
			return;
		}

		webrtc::scoped_refptr<webrtc::VideoFrameBuffer> buffer = frame.video_frame_buffer();
		webrtc::scoped_refptr<webrtc::I420BufferInterface> i420Buffer = buffer->ToI420();

		if (i420Buffer == nullptr) {
			return;
		}

		// The copy keeps sinks from seeing what another one writes into its
		// pixels, at the cost of a full copy of each frame for each sink. A
		// shared sink gets the pixels the source handed out instead, which it
		// can only read, so that sinks and the encoder share one buffer.
		webrtc::scoped_refptr<webrtc::I420BufferInterface> javaBuffer = i420Buffer;

		if (!shared) {
			javaBuffer = webrtc::I420Buffer::Copy(*i420Buffer);
		}

		jint rotation = static_cast<jint>(frame.rotation());
		jlong timestamp = frame.timestamp_us() * webrtc::kNumNanosecsPerMicrosec;

		try {
			JavaLocalRef<jobject> jBuffer = I420Buffer::toJava(env, javaBuffer, shared);

			// The reference the Java frame gives up when it is released.
			javaBuffer->AddRef();

			JavaLocalRef<jobject> jFrame(env, env->NewObject(javaFrameClass->cls, javaFrameClass->ctor, jBuffer.get(), rotation, timestamp));

			if (jFrame.get() == nullptr) {
				// No frame got the buffer that would release it.
				javaBuffer->Release();
				ExceptionCheck(env);
			}
			else {
				env->CallVoidMethod(sink, javaClass->onFrame, jFrame.get());
			}
		}
		catch (...) {
			ThrowCxxJavaException(env);
		}

		ReportPendingException(env);
	}

	VideoTrackSink::JavaVideoTrackSinkClass::JavaVideoTrackSinkClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG_VIDEO"VideoTrackSink");

		onFrame = GetMethod(env, cls, "onVideoFrame", "(L" PKG_VIDEO "VideoFrame;)V");
	}
}
