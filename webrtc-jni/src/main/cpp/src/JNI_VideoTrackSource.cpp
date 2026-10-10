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

#include "JNI_VideoTrackSource.h"
#include "api/VideoFrame.h"
#include "media/video/VideoProcessorHost.h"
#include "JavaClasses.h"
#include "JavaNullPointerException.h"
#include "JavaObject.h"
#include "JavaRef.h"
#include "JavaRuntimeException.h"
#include "JavaUtils.h"

#include "api/video/video_frame.h"
#include "api/video/video_rotation.h"

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_VideoTrackSource_setVideoProcessorNative
(JNIEnv * env, jobject caller, jobject jProcessor)
{
	jni::VideoProcessorHost * host = GetHandle<jni::VideoProcessorHost>(env, caller, "processorHostHandle");

	if (host == nullptr) {
		env->Throw(jni::JavaRuntimeException(env, "The video source does not support a VideoProcessor"));
		return;
	}

	try {
		host->SetVideoProcessor(env, jProcessor);
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_VideoTrackSource_deliverProcessedFrame
(JNIEnv * env, jobject caller, jobject jFrame)
{
	if (jFrame == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "VideoFrame must not be null"));
		return;
	}

	jni::VideoProcessorHost * host = GetHandle<jni::VideoProcessorHost>(env, caller, "processorHostHandle");

	// The source has been disposed.
	if (host == nullptr) {
		return;
	}

	try {
		const auto frameClass = jni::JavaClasses::get<jni::JavaVideoFrameClass>(env);

		const jni::JavaLocalRef<jobject> frameRef(env, jFrame);
		jni::JavaObject frame(env, frameRef);

		const jint rotation = frame.getInt(frameClass->rotation);
		const jlong timestampNs = frame.getLong(frameClass->timestampNs);

		webrtc::scoped_refptr<webrtc::VideoFrameBuffer> buffer =
			jni::VideoFrame::toNativeBuffer(env, frame.getObject(frameClass->buffer));

		if (buffer == nullptr) {
			env->Throw(jni::JavaRuntimeException(env, "The buffer of the VideoFrame cannot be read"));
			return;
		}

		// Java only allows multiples of 90.
		const int degrees = ((rotation % 360) + 360) % 360;

		host->DeliverProcessedFrame(webrtc::VideoFrame::Builder()
			.set_video_frame_buffer(buffer)
			.set_rotation(static_cast<webrtc::VideoRotation>(degrees))
			.set_timestamp_us(timestampNs / 1000)
			.build());
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}
