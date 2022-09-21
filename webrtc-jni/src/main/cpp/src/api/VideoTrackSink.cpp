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
#include "JavaClasses.h"
#include "JNI_WebRTC.h"

#include "api/video/i420_buffer.h"
#include "rtc_base/time_utils.h"

namespace jni
{
	VideoTrackSink::VideoTrackSink(JNIEnv * env, const JavaGlobalRef<jobject> & sink) :
		sink(sink),
		javaClass(JavaClasses::get<JavaVideoTrackSinkClass>(env)),
		javaFrameClass(JavaClasses::get<JavaVideoFrameClass>(env)),
		javaBufferClass(JavaClasses::get<JavaNativeI420BufferClass>(env))
	{
	}

	void VideoTrackSink::OnFrame(const webrtc::VideoFrame & frame)
	{
		JNIEnv * env = AttachCurrentThread();
		
		rtc::scoped_refptr<webrtc::VideoFrameBuffer> buffer = frame.video_frame_buffer();
		rtc::scoped_refptr<webrtc::I420BufferInterface> i420Buffer = buffer->ToI420();

//		if (frame.rotation() != webrtc::kVideoRotation_0) {
//			i420Buffer = webrtc::I420Buffer::Rotate(*i420Buffer, frame.rotation());
//		}

		jint rotation = static_cast<jint>(frame.rotation());
		jlong timestamp = frame.timestamp_us() * rtc::kNumNanosecsPerMicrosec;
		
		JavaLocalRef<jobject> jBuffer = I420Buffer::toJava(env, i420Buffer);
		jobject jFrame = env->NewObject(javaFrameClass->cls, javaFrameClass->ctor, jBuffer.get(), rotation, timestamp);

		env->CallVoidMethod(sink, javaClass->onFrame, jFrame);
		env->DeleteLocalRef(jBuffer);
		env->DeleteLocalRef(jFrame);
	}

	VideoTrackSink::JavaVideoTrackSinkClass::JavaVideoTrackSinkClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG_VIDEO"VideoTrackSink");

		onFrame = GetMethod(env, cls, "onVideoFrame", "(L" PKG_VIDEO "VideoFrame;)V");
	}
}
