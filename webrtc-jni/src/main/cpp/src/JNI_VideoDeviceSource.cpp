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

#include "JNI_VideoDeviceSource.h"
#include "api/VideoTrackSink.h"
#include "media/video/VideoDevice.h"
#include "media/video/VideoTrackDeviceSource.h"
#include "JavaRef.h"
#include "JavaObject.h"
#include "JavaString.h"
#include "JavaUtils.h"

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_VideoDeviceSource_setVideoCaptureDevice
(JNIEnv * env, jobject caller, jobject device)
{
	if (!device) {
		env->Throw(jni::JavaNullPointerException(env, "VideoDevice is null"));
		return;
	}

	jni::VideoTrackDeviceSource * videoSource = GetHandle<jni::VideoTrackDeviceSource>(env, caller);
	CHECK_HANDLE(videoSource);

	const auto dev = jni::VideoDevice::toNativeVideoDevice(env, jni::JavaLocalRef<jobject>(env, device));

	videoSource->setVideoDevice(std::make_shared<jni::avdev::VideoDevice>(dev.getName(), dev.getDescriptor()));
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_VideoDeviceSource_setVideoCaptureCapability
(JNIEnv * env, jobject caller, jobject jcapability)
{
	jni::VideoTrackDeviceSource * videoSource = GetHandle<jni::VideoTrackDeviceSource>(env, caller);
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

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_VideoDeviceSource_start
(JNIEnv * env, jobject caller)
{
	jni::VideoTrackDeviceSource * videoSource = GetHandle<jni::VideoTrackDeviceSource>(env, caller);
	CHECK_HANDLE(videoSource);

	try {
		videoSource->start();
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_VideoDeviceSource_stop
(JNIEnv * env, jobject caller)
{
	jni::VideoTrackDeviceSource * videoSource = GetHandle<jni::VideoTrackDeviceSource>(env, caller);
	CHECK_HANDLE(videoSource);

	try {
		videoSource->stop();
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_VideoDeviceSource_dispose
(JNIEnv * env, jobject caller)
{
	jni::VideoTrackDeviceSource * videoSource = GetHandle<jni::VideoTrackDeviceSource>(env, caller);
	CHECK_HANDLE(videoSource);

	rtc::RefCountReleaseStatus status = videoSource->Release();

	if (status != rtc::RefCountReleaseStatus::kDroppedLastRef) {
		RTC_LOG(LS_WARNING) << "Native object was not deleted. A reference is still around somewhere.";
	}

	SetHandle<std::nullptr_t>(env, caller, nullptr);

	videoSource = nullptr;
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_VideoDeviceSource_initialize
(JNIEnv * env, jobject caller)
{
	rtc::scoped_refptr<jni::VideoTrackDeviceSource> videoSource = new rtc::RefCountedObject<jni::VideoTrackDeviceSource>();

	SetHandle(env, caller, videoSource.release());
}