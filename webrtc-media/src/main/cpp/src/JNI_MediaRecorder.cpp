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
#include "media/JniBoundary.h"
#include "media/MediaRecorder.h"
#include "webrtc_java_api.h"

#include <memory>
#include <string>
#include <vector>

namespace
{
	ffmpeg::MediaRecorder * RecorderOf(jlong handle)
	{
		return reinterpret_cast<ffmpeg::MediaRecorder *>(handle);
	}

	// The table of the webrtc-java API, or null with an IOException thrown.
	const webrtc_java_api * ApiOf(JNIEnv * env, jlong tableAddress)
	{
		const webrtc_java_api * api = reinterpret_cast<const webrtc_java_api *>(tableAddress);
		std::string apiError = ffmpeg::CheckApi(api);

		if (!apiError.empty()) {
			ffmpeg::ThrowIOException(env, apiError);

			return nullptr;
		}

		return api;
	}

	// Copies a Java string as UTF-8. Returns false with an exception
	// pending if it cannot.
	bool CopyString(JNIEnv * env, jstring string, std::string * utf8)
	{
		ffmpeg::JavaStringChars chars(env, string);

		if (chars.get() == nullptr) {
			return false;
		}

		*utf8 = chars.utf8();

		return true;
	}

	// Opens the recorder and gives it the Java recorder to report to, or
	// throws, naming what it was to record to.
	jlong Finish(JNIEnv * env, jobject caller, std::unique_ptr<ffmpeg::MediaRecorder> recorder,
			const std::string & target)
	{
		int result = recorder->Open();

		if (result < 0) {
			recorder.reset();

			ffmpeg::ThrowIOException(env, "Opening " + target + " for recording failed: " + ffmpeg::ErrorText(result));

			return 0;
		}

		recorder->SetObserver(std::make_unique<ffmpeg::JavaRecorderObserver>(env, caller));

		return reinterpret_cast<jlong>(recorder.release());
	}
}

JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_media_recorder_MediaRecorder_create
(JNIEnv * env, jobject caller, jstring jPath, jlong tableAddress)
{
	return ffmpeg::CallFromJava<jlong>(env, 0, [&]() -> jlong {
		const webrtc_java_api * api = ApiOf(env, tableAddress);
		std::string path;

		if (api == nullptr || !CopyString(env, jPath, &path)) {
			return 0;
		}

		return Finish(env, caller, std::make_unique<ffmpeg::MediaRecorder>(api, path), path);
	});
}

JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_media_recorder_MediaRecorder_createSegments
(JNIEnv * env, jobject caller, jstring jPrefix, jstring jSuffix, jint width, jlong firstIndex,
	jlong maxDurationUs, jlong maxBytes, jlong tableAddress)
{
	return ffmpeg::CallFromJava<jlong>(env, 0, [&]() -> jlong {
		const webrtc_java_api * api = ApiOf(env, tableAddress);

		if (api == nullptr) {
			return 0;
		}

		ffmpeg::MediaRecorder::SegmentConfig segments;

		if (!CopyString(env, jPrefix, &segments.prefix) || !CopyString(env, jSuffix, &segments.suffix)) {
			return 0;
		}

		segments.width = static_cast<int>(width);
		segments.first_index = static_cast<int64_t>(firstIndex);
		segments.max_duration_us = static_cast<int64_t>(maxDurationUs);
		segments.max_bytes = static_cast<int64_t>(maxBytes);

		const std::string target = segments.prefix + "*" + segments.suffix;

		return Finish(env, caller, std::make_unique<ffmpeg::MediaRecorder>(api, std::move(segments)), target);
	});
}

JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_media_recorder_MediaRecorder_createReplay
(JNIEnv * env, jobject caller, jlong windowUs, jlong maxBytes, jlong tableAddress)
{
	return ffmpeg::CallFromJava<jlong>(env, 0, [&]() -> jlong {
		const webrtc_java_api * api = ApiOf(env, tableAddress);

		if (api == nullptr) {
			return 0;
		}

		ffmpeg::MediaRecorder::ReplayConfig replay;
		replay.window_us = static_cast<int64_t>(windowUs);
		replay.max_bytes = static_cast<int64_t>(maxBytes);

		return Finish(env, caller, std::make_unique<ffmpeg::MediaRecorder>(api, replay), "a replay buffer");
	});
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_media_recorder_MediaRecorder_addTrack
(JNIEnv * env, jclass caller, jlong handle, jlong frames)
{
	if (handle == 0 || frames == 0) {
		return -1;
	}

	// A track that fails to be added has its frames released already.
	return ffmpeg::CallFromJava<jint>(env, -1, [&]() -> jint {
		return RecorderOf(handle)->AddTrack(reinterpret_cast<void *>(frames));
	});
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_recorder_MediaRecorder_start
(JNIEnv * env, jclass caller, jlong handle)
{
	ffmpeg::CallFromJava(env, [&] {
		if (handle != 0) {
			RecorderOf(handle)->Start();
		}
	});
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_media_recorder_MediaRecorder_stop
(JNIEnv * env, jclass caller, jlong handle)
{
	const jint failed = static_cast<jint>(ffmpeg::MediaRecorder::Result::kFailed);

	if (handle == 0) {
		return failed;
	}

	return ffmpeg::CallFromJava<jint>(env, failed, [&]() -> jint {
		return static_cast<jint>(RecorderOf(handle)->Stop());
	});
}

JNIEXPORT jlongArray JNICALL Java_dev_onvoid_webrtc_media_recorder_MediaRecorder_stats
(JNIEnv * env, jclass caller, jlong handle)
{
	return ffmpeg::CallFromJava<jlongArray>(env, nullptr, [&]() -> jlongArray {
		const ffmpeg::MediaRecorder::Stats stats = RecorderOf(handle)->GetStats();

		// In the order MediaRecorderStats reads them.
		std::vector<jlong> values;

		values.reserve(1 + stats.tracks.size() * 3);
		values.push_back(stats.max_queued_bytes);

		for (const auto & track : stats.tracks) {
			values.push_back(track.dropped_queue_full);
			values.push_back(track.dropped_allocation_failed);
			values.push_back(track.dropped_awaiting_key_frame);
		}

		const jsize size = static_cast<jsize>(values.size());
		jlongArray array = env->NewLongArray(size);

		if (array != nullptr) {
			env->SetLongArrayRegion(array, 0, size, values.data());
		}

		return array;
	});
}

JNIEXPORT jboolean JNICALL Java_dev_onvoid_webrtc_media_recorder_MediaRecorder_saveReplay
(JNIEnv * env, jclass caller, jlong handle, jint id, jstring jPath)
{
	if (handle == 0) {
		return JNI_FALSE;
	}

	return ffmpeg::CallFromJava<jboolean>(env, JNI_FALSE, [&]() -> jboolean {
		std::string path;

		if (!CopyString(env, jPath, &path)) {
			return JNI_FALSE;
		}

		return RecorderOf(handle)->SaveReplay(static_cast<int>(id), std::move(path)) ? JNI_TRUE : JNI_FALSE;
	});
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_recorder_MediaRecorder_sync
(JNIEnv * env, jclass caller, jlong handle)
{
	if (handle == 0) {
		return;
	}

	ffmpeg::CallFromJava(env, [&]() {
		RecorderOf(handle)->Sync();
	});
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_recorder_MediaRecorder_dispose
(JNIEnv * env, jclass caller, jlong handle)
{
	if (handle != 0) {
		delete RecorderOf(handle);
	}
}
