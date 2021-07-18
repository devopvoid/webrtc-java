/*
 * Copyright 2021 Alex Andres
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

#include "JNI_VideoCapture.h"
#include "api/VideoTrackSink.h"
#include "media/video/VideoCapture.h"
#include "media/video/VideoDevice.h"
#include "JavaRef.h"
#include "JavaObject.h"
#include "JavaString.h"
#include "JavaUtils.h"

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_VideoCapture_setVideoCaptureDevice
(JNIEnv * env, jobject caller, jobject device)
{
	if (!device) {
		env->Throw(jni::JavaNullPointerException(env, "VideoDevice is null"));
		return;
	}

	jni::VideoCapture * videoSource = GetHandle<jni::VideoCapture>(env, caller);
	CHECK_HANDLE(videoSource);

	const auto dev = jni::VideoDevice::toNativeVideoDevice(env, jni::JavaLocalRef<jobject>(env, device));

	videoSource->setDevice(std::make_shared<jni::avdev::VideoDevice>(dev.getName(), dev.getDescriptor()));
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_VideoCapture_setVideoCaptureCapability
(JNIEnv * env, jobject caller, jobject jcapability)
{
	jni::VideoCapture * videoSource = GetHandle<jni::VideoCapture>(env, caller);
	CHECK_HANDLE(videoSource);

	if (!jcapability) {
		env->Throw(jni::JavaNullPointerException(env, "VideoCaptureCapability is null"));
		return;
	}

	jint width = env->GetIntField(jcapability, GetFieldID(env, jcapability, "width", "I"));
	jint height = env->GetIntField(jcapability, GetFieldID(env, jcapability, "height", "I"));
	jint frameRate = env->GetIntField(jcapability, GetFieldID(env, jcapability, "frameRate", "I"));

	webrtc::VideoCaptureCapability capability;
	capability.width = static_cast<int32_t>(width);
	capability.height = static_cast<int32_t>(height);
	capability.maxFPS = static_cast<int32_t>(frameRate);

	videoSource->setVideoCaptureCapability(capability);
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_VideoCapture_setVideoSink
(JNIEnv * env, jobject caller, jobject jsink)
{
	if (jsink == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "VideoTrackSink must not be null"));
		return;
	}

	jni::VideoCapture * videoSource = GetHandle<jni::VideoCapture>(env, caller);
	CHECK_HANDLE(videoSource);

	videoSource->setVideoSink(std::make_unique<jni::VideoTrackSink>(env, jni::JavaGlobalRef<jobject>(env, jsink)));
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_VideoCapture_start
(JNIEnv * env, jobject caller)
{
	jni::VideoCapture * videoSource = GetHandle<jni::VideoCapture>(env, caller);
	CHECK_HANDLE(videoSource);

	try {
		videoSource->start();
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_VideoCapture_stop
(JNIEnv * env, jobject caller)
{
	jni::VideoCapture * videoSource = GetHandle<jni::VideoCapture>(env, caller);
	CHECK_HANDLE(videoSource);

	try {
		videoSource->stop();
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_VideoCapture_dispose
(JNIEnv * env, jobject caller)
{
	jni::VideoCapture * videoSource = GetHandle<jni::VideoCapture>(env, caller);
	CHECK_HANDLE(videoSource);

	SetHandle<std::nullptr_t>(env, caller, nullptr);

	videoSource = nullptr;
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_VideoCapture_initialize
(JNIEnv * env, jobject caller)
{
	SetHandle(env, caller, new jni::VideoCapture());
}