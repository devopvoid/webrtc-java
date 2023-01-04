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

#include "JNI_VideoDesktopSource.h"
#include "api/VideoTrackSink.h"
#include "media/video/VideoTrackDesktopSource.h"
#include "JavaRef.h"
#include "JavaObject.h"
#include "JavaString.h"
#include "JavaUtils.h"
#include "rtc_base/logging.h"
#include "rtc_base/ref_counted_object.h"

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_VideoDesktopSource_setSourceId
(JNIEnv * env, jobject caller, jlong sourceId, jboolean isWindow)
{
	jni::VideoTrackDesktopSource * videoSource = GetHandle<jni::VideoTrackDesktopSource>(env, caller);
	CHECK_HANDLE(videoSource);

	videoSource->setSourceId(static_cast<webrtc::DesktopCapturer::SourceId>(sourceId), static_cast<bool>(isWindow));
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_VideoDesktopSource_setFrameRate
(JNIEnv * env, jobject caller, jint frameRate)
{
	jni::VideoTrackDesktopSource * videoSource = GetHandle<jni::VideoTrackDesktopSource>(env, caller);
	CHECK_HANDLE(videoSource);

	videoSource->setFrameRate(static_cast<uint16_t>(frameRate));
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_VideoDesktopSource_setMaxFrameSize
(JNIEnv* env, jobject caller, jint width, jint height)
{
	jni::VideoTrackDesktopSource * videoSource = GetHandle<jni::VideoTrackDesktopSource>(env, caller);
	CHECK_HANDLE(videoSource);

	videoSource->setMaxFrameSize(webrtc::DesktopSize(width, height));
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_VideoDesktopSource_setFocusSelectedSource
(JNIEnv * env, jobject caller, jboolean focus)
{
	jni::VideoTrackDesktopSource * videoSource = GetHandle<jni::VideoTrackDesktopSource>(env, caller);
	CHECK_HANDLE(videoSource);

	videoSource->setFocusSelectedSource(focus);
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_VideoDesktopSource_start
(JNIEnv * env, jobject caller)
{
	jni::VideoTrackDesktopSource * videoSource = GetHandle<jni::VideoTrackDesktopSource>(env, caller);
	CHECK_HANDLE(videoSource);

	try {
		videoSource->start();
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_VideoDesktopSource_stop
(JNIEnv * env, jobject caller)
{
	jni::VideoTrackDesktopSource * videoSource = GetHandle<jni::VideoTrackDesktopSource>(env, caller);
	CHECK_HANDLE(videoSource);

	try {
		videoSource->stop();
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_VideoDesktopSource_dispose
(JNIEnv * env, jobject caller)
{
	jni::VideoTrackDesktopSource * videoSource = GetHandle<jni::VideoTrackDesktopSource>(env, caller);
	CHECK_HANDLE(videoSource);

	rtc::RefCountReleaseStatus status = videoSource->Release();

	if (status != rtc::RefCountReleaseStatus::kDroppedLastRef) {
		RTC_LOG(LS_WARNING) << "Native object was not deleted. A reference is still around somewhere.";
	}

	SetHandle<std::nullptr_t>(env, caller, nullptr);

	videoSource = nullptr;
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_VideoDesktopSource_initialize
(JNIEnv * env, jobject caller)
{
	rtc::scoped_refptr<jni::VideoTrackDesktopSource> videoSource = new rtc::RefCountedObject<jni::VideoTrackDesktopSource>();

	SetHandle(env, caller, videoSource.release());
}