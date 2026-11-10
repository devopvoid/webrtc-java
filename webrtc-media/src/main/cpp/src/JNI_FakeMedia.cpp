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

#include "JNI_FakeMedia.h"
#include "webrtc_java_api.h"

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <new>
#include <vector>

// The Java class this belongs to exists in the tests only. It stands in for
// the webrtc-java library a recorder takes its frames and its clock from, so
// that a test decides what arrives when, and what time it is: what a
// recorder makes of its frames then follows from them alone, however fast
// the machine is.

namespace
{
	std::atomic<int64_t> clock_us{ 0 };

	struct Observer;

	// What a sender or receiver would be: the frames a test pushes go to the
	// observers attached to it.
	struct Frames
	{
		// Held while frames are delivered, so that an observer removed is
		// not called any more once the removal returns.
		std::mutex mutex;
		std::vector<Observer *> observers;
		std::atomic<int> references{ 1 };
	};

	struct Observer
	{
		Frames * frames;
		wj_encoded_frame_fn fn;
		void * opaque;
	};

	Frames * FramesOf(jlong handle)
	{
		return reinterpret_cast<Frames *>(handle);
	}

	void Retain(Frames * frames)
	{
		frames->references.fetch_add(1);
	}

	void Release(Frames * frames)
	{
		if (frames->references.fetch_sub(1) == 1) {
			delete frames;
		}
	}

	int64_t NowUs()
	{
		return clock_us.load();
	}

	int VideoSourcePush(void *, const wj_i420_frame *)
	{
		return WEBRTC_JAVA_ERR_HANDLE;
	}

	int AudioSourcePush(void *, const wj_audio_chunk *)
	{
		return WEBRTC_JAVA_ERR_HANDLE;
	}

	int VideoSourcePushColor(void *, const wj_i420_frame *, const wj_color_space *)
	{
		return WEBRTC_JAVA_ERR_HANDLE;
	}

	void SourceRetain(void *)
	{
	}

	void SourceRelease(void *)
	{
	}

	void * ObserverAdd(void * handle, wj_encoded_frame_fn fn, void * opaque)
	{
		if (handle == nullptr || fn == nullptr) {
			return nullptr;
		}

		Frames * frames = static_cast<Frames *>(handle);
		Observer * observer = new (std::nothrow) Observer{ frames, fn, opaque };

		if (observer == nullptr) {
			return nullptr;
		}

		std::lock_guard<std::mutex> lock(frames->mutex);

		frames->observers.push_back(observer);

		// The observer keeps what it observes alive, as the library's do.
		Retain(frames);

		return observer;
	}

	void ObserverRemove(void * handle)
	{
		Observer * observer = static_cast<Observer *>(handle);
		Frames * frames = observer->frames;

		{
			std::lock_guard<std::mutex> lock(frames->mutex);

			auto & observers = frames->observers;
			observers.erase(std::remove(observers.begin(), observers.end(), observer), observers.end());
		}

		delete observer;

		Release(frames);
	}

	void FramesRelease(void * handle)
	{
		if (handle != nullptr) {
			Release(static_cast<Frames *>(handle));
		}
	}

	const webrtc_java_api * Table()
	{
		static const webrtc_java_api table = [] {
			webrtc_java_api api{};

			api.version = WEBRTC_JAVA_API_VERSION;
			api.size = sizeof(webrtc_java_api);
			api.now_us = &NowUs;
			api.video_source_push = &VideoSourcePush;
			api.audio_source_push = &AudioSourcePush;
			api.video_source_retain = &SourceRetain;
			api.video_source_release = &SourceRelease;
			api.audio_source_retain = &SourceRetain;
			api.audio_source_release = &SourceRelease;
			api.encoded_observer_add = &ObserverAdd;
			api.encoded_observer_remove = &ObserverRemove;
			api.encoded_frames_release = &FramesRelease;
			api.video_source_push_color = &VideoSourcePushColor;

			return api;
		}();

		return &table;
	}
}

JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_media_recorder_FakeMedia_tableAddress
(JNIEnv * env, jclass caller)
{
	return reinterpret_cast<jlong>(Table());
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_recorder_FakeMedia_setClock
(JNIEnv * env, jclass caller, jlong nowUs)
{
	clock_us.store(nowUs);
}

JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_media_recorder_FakeMedia_createFrames
(JNIEnv * env, jclass caller)
{
	return reinterpret_cast<jlong>(new (std::nothrow) Frames());
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_recorder_FakeMedia_retainFrames
(JNIEnv * env, jclass caller, jlong handle)
{
	if (handle != 0) {
		Retain(FramesOf(handle));
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_recorder_FakeMedia_releaseFrames
(JNIEnv * env, jclass caller, jlong handle)
{
	if (handle != 0) {
		Release(FramesOf(handle));
	}
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_media_recorder_FakeMedia_observers
(JNIEnv * env, jclass caller, jlong handle)
{
	if (handle == 0) {
		return 0;
	}

	Frames * frames = FramesOf(handle);
	std::lock_guard<std::mutex> lock(frames->mutex);

	return static_cast<jint>(frames->observers.size());
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_recorder_FakeMedia_push
(JNIEnv * env, jclass caller, jlong handle, jboolean video, jstring jMimeType, jint ssrc,
	jint rtpTimestamp, jboolean keyFrame, jint width, jint height, jlong timeUs, jbyteArray jData)
{
	if (handle == 0 || jMimeType == nullptr || jData == nullptr) {
		return;
	}

	const jsize size = env->GetArrayLength(jData);
	std::vector<uint8_t> data(static_cast<size_t>(size));

	env->GetByteArrayRegion(jData, 0, size, reinterpret_cast<jbyte *>(data.data()));

	if (env->ExceptionCheck()) {
		return;
	}

	const char * mimeType = env->GetStringUTFChars(jMimeType, nullptr);

	if (mimeType == nullptr) {
		return;
	}

	wj_encoded_frame frame{};
	frame.data = data.data();
	frame.size = data.size();
	frame.media_type = video ? WEBRTC_JAVA_MEDIA_VIDEO : WEBRTC_JAVA_MEDIA_AUDIO;
	frame.mime_type = mimeType;
	frame.payload_type = video ? 96 : 111;
	frame.ssrc = static_cast<uint32_t>(ssrc);
	frame.rtp_timestamp = static_cast<uint32_t>(rtpTimestamp);
	frame.key_frame = video && keyFrame ? 1 : 0;
	frame.width = width;
	frame.height = height;
	frame.time_us = timeUs;

	{
		Frames * frames = FramesOf(handle);
		std::lock_guard<std::mutex> lock(frames->mutex);

		for (Observer * observer : frames->observers) {
			observer->fn(observer->opaque, &frame);
		}
	}

	env->ReleaseStringUTFChars(jMimeType, mimeType);
}
