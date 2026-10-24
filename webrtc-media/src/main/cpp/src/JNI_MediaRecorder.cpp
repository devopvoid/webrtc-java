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

#include "JNI_MediaRecorder.h"
#include "media/ApiCheck.h"
#include "media/ErrorText.h"
#include "media/JavaRecorderObserver.h"
#include "media/MediaRecorder.h"
#include "webrtc_java_api.h"

#include <memory>
#include <string>

namespace
{
	void ThrowIOException(JNIEnv * env, const std::string & message)
	{
		jclass cls = env->FindClass("java/io/IOException");

		if (cls != nullptr) {
			env->ThrowNew(cls, message.c_str());
			env->DeleteLocalRef(cls);
		}
	}

	ffmpeg::MediaRecorder * RecorderOf(jlong handle)
	{
		return reinterpret_cast<ffmpeg::MediaRecorder *>(handle);
	}
}

JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_media_recorder_MediaRecorder_create
(JNIEnv * env, jobject caller, jstring jPath, jlong tableAddress)
{
	const webrtc_java_api * api = reinterpret_cast<const webrtc_java_api *>(tableAddress);
	std::string apiError = ffmpeg::CheckApi(api);

	if (!apiError.empty()) {
		ThrowIOException(env, apiError);

		return 0;
	}

	const char * chars = env->GetStringUTFChars(jPath, nullptr);

	if (chars == nullptr) {
		return 0;
	}

	std::string path(chars);

	env->ReleaseStringUTFChars(jPath, chars);

	auto recorder = std::make_unique<ffmpeg::MediaRecorder>(api, path);

	int result = recorder->Open();

	if (result < 0) {
		recorder.reset();

		ThrowIOException(env, "Opening " + path + " for recording failed: " + ffmpeg::ErrorText(result));

		return 0;
	}

	recorder->SetObserver(std::make_unique<ffmpeg::JavaRecorderObserver>(env, caller));

	return reinterpret_cast<jlong>(recorder.release());
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_media_recorder_MediaRecorder_addTrack
(JNIEnv * env, jclass caller, jlong handle, jlong frames)
{
	if (handle == 0 || frames == 0) {
		return -1;
	}

	return RecorderOf(handle)->AddTrack(reinterpret_cast<void *>(frames));
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_recorder_MediaRecorder_start
(JNIEnv * env, jclass caller, jlong handle)
{
	if (handle != 0) {
		RecorderOf(handle)->Start();
	}
}

JNIEXPORT jboolean JNICALL Java_dev_onvoid_webrtc_media_recorder_MediaRecorder_stop
(JNIEnv * env, jclass caller, jlong handle)
{
	if (handle == 0) {
		return JNI_FALSE;
	}

	return RecorderOf(handle)->Stop() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_recorder_MediaRecorder_dispose
(JNIEnv * env, jclass caller, jlong handle)
{
	if (handle != 0) {
		delete RecorderOf(handle);
	}
}
