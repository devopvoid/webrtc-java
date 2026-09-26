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

#include "JNI_MediaPlayer.h"
#include "media/ErrorText.h"
#include "media/JavaPlayerObserver.h"
#include "media/MediaPlayer.h"
#include "media/MediaReader.h"
#include "webrtc_java_api.h"

#include <memory>
#include <string>

extern "C" {
#include <libavutil/error.h>
}

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

	ffmpeg::MediaPlayer * PlayerOf(jlong handle)
	{
		return reinterpret_cast<ffmpeg::MediaPlayer *>(handle);
	}
}

JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_media_player_MediaPlayer_create
(JNIEnv * env, jobject caller, jlong readerHandle, jlong tableAddress,
		jlong videoSourceHandle, jlong audioSourceHandle)
{
	// The Java side detached the reader before this call, so it is ours now
	// and has to be released here if anything goes wrong.
	std::unique_ptr<ffmpeg::MediaReader> reader(
			reinterpret_cast<ffmpeg::MediaReader *>(readerHandle));

	if (reader == nullptr) {
		ThrowIOException(env, "The reader is closed");

		return 0;
	}

	const webrtc_java_api * api =
			reinterpret_cast<const webrtc_java_api *>(tableAddress);

	if (api == nullptr) {
		ThrowIOException(env, "The webrtc-java function table is not available");

		return 0;
	}
	if (api->version != WEBRTC_JAVA_API_VERSION) {
		ThrowIOException(env, "This module was built against webrtc-java interface version "
				+ std::to_string(WEBRTC_JAVA_API_VERSION) + ", but the loaded library provides "
				+ std::to_string(api->version));

		return 0;
	}
	if (api->size < sizeof(webrtc_java_api)) {
		// The same version, but from before the members this module relies
		// on were appended.
		ThrowIOException(env, "The loaded webrtc-java library provides "
				+ std::to_string(api->size) + " bytes of its interface, but this module needs "
				+ std::to_string(sizeof(webrtc_java_api)));

		return 0;
	}

	auto player = std::make_unique<ffmpeg::MediaPlayer>(std::move(reader), api,
			reinterpret_cast<void *>(videoSourceHandle),
			reinterpret_cast<void *>(audioSourceHandle));

	player->SetObserver(std::make_unique<ffmpeg::JavaPlayerObserver>(env, caller));

	int result = player->Initialize();

	if (result < 0) {
		// Released before throwing: closing reports the closed state to the
		// observer, which calls into Java, and a call into Java with an
		// exception pending is not allowed, and would also clear it. Nothing
		// is listening yet anyway, so the observer goes first.
		player->SetObserver(nullptr);
		player.reset();

		ThrowIOException(env, "Opening the decoders failed: " + ffmpeg::ErrorText(result));

		return 0;
	}

	return reinterpret_cast<jlong>(player.release());
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_player_MediaPlayer_start
(JNIEnv * env, jclass caller, jlong handle)
{
	if (handle != 0) {
		PlayerOf(handle)->Play();
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_player_MediaPlayer_suspend
(JNIEnv * env, jclass caller, jlong handle)
{
	if (handle != 0) {
		PlayerOf(handle)->Pause();
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_player_MediaPlayer_seek
(JNIEnv * env, jclass caller, jlong handle, jlong positionUs)
{
	if (handle != 0) {
		PlayerOf(handle)->Seek(positionUs);
	}
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_player_MediaPlayer_setLooping
(JNIEnv * env, jclass caller, jlong handle, jboolean looping)
{
	if (handle != 0) {
		PlayerOf(handle)->SetLooping(looping == JNI_TRUE);
	}
}

JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_media_player_MediaPlayer_position
(JNIEnv * env, jclass caller, jlong handle)
{
	return handle != 0 ? PlayerOf(handle)->GetPositionUs() : 0;
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_media_player_MediaPlayer_state
(JNIEnv * env, jclass caller, jlong handle)
{
	// A player that is gone is closed, which is what the Java side reports
	// once it has let go of the handle.
	return handle != 0 ? PlayerOf(handle)->GetState() : ffmpeg::kClosed;
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_player_MediaPlayer_dispose
(JNIEnv * env, jclass caller, jlong handle)
{
	// Closing twice is allowed, so a handle that is already zero is simply
	// nothing to do.
	if (handle != 0) {
		delete PlayerOf(handle);
	}
}
