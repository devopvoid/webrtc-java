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

#include "JNI_DesktopCapturer.h"
#include "JavaArrayList.h"
#include "JavaError.h"
#include "JavaUtils.h"
#include "media/video/desktop/DesktopCapturer.h"
#include "media/video/desktop/DesktopCaptureCallback.h"
#include "media/video/desktop/DesktopSource.h"

#include "modules/desktop_capture/desktop_capturer.h"

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_desktop_DesktopCapturer_dispose
(JNIEnv * env, jobject caller)
{
	jni::DesktopCapturer * capturer = GetHandle<jni::DesktopCapturer>(env, caller);
	CHECK_HANDLE(capturer);

	delete capturer;

	SetHandle<std::nullptr_t>(env, caller, nullptr);

	auto callback = GetHandle<jni::DesktopCaptureCallback>(env, caller, "callbackHandle");

	if (callback) {
		delete callback;
	}
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_media_video_desktop_DesktopCapturer_getDesktopSources
(JNIEnv * env, jobject caller)
{
	jni::DesktopCapturer * capturer = GetHandle<jni::DesktopCapturer>(env, caller);
	CHECK_HANDLEV(capturer, nullptr);

	webrtc::DesktopCapturer::SourceList sources;

	if (!capturer->GetSourceList(&sources)) {
		env->Throw(jni::JavaError(env, "Get source list failed"));
		return nullptr;
	}

	jni::JavaArrayList sourceList(env, sources.size());

	for (const auto & source : sources) {
		sourceList.add(jni::DesktopSource::toJava(env, source));
	}

	return sourceList.listObject().release();
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_desktop_DesktopCapturer_selectSource
(JNIEnv * env, jobject caller, jobject jsource)
{
	jni::DesktopCapturer * capturer = GetHandle<jni::DesktopCapturer>(env, caller);
	CHECK_HANDLE(capturer);

	auto source = jni::DesktopSource::toNative(env, jni::JavaLocalRef<jobject>(env, jsource));

	if (!capturer->SelectSource(source.id)) {
		env->Throw(jni::JavaError(env, "Select source failed"));
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_desktop_DesktopCapturer_setFocusSelectedSource
(JNIEnv * env, jobject caller, jboolean focus)
{
	jni::DesktopCapturer * capturer = GetHandle<jni::DesktopCapturer>(env, caller);
	CHECK_HANDLE(capturer);

	capturer->setFocusSelectedSource(focus);
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_desktop_DesktopCapturer_start
(JNIEnv * env, jobject caller, jobject jcallback)
{
	if (jcallback == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "DesktopCaptureCallback is null"));
		return;
	}

	jni::DesktopCapturer * capturer = GetHandle<jni::DesktopCapturer>(env, caller);
	CHECK_HANDLE(capturer);

	auto callback = new jni::DesktopCaptureCallback(env, jni::JavaGlobalRef<jobject>(env, jcallback));

	try {
		SetHandle(env, caller, "callbackHandle", callback);

		capturer->Start(callback);
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_video_desktop_DesktopCapturer_captureFrame
(JNIEnv * env, jobject caller)
{
	jni::DesktopCapturer * capturer = GetHandle<jni::DesktopCapturer>(env, caller);
	CHECK_HANDLE(capturer);

	try {
		capturer->CaptureFrame();
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}