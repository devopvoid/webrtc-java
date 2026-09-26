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

#include "JNI_MediaReader.h"
#include "media/MediaReader.h"

#include <string>

extern "C" {
#include <libavutil/error.h>
}

namespace
{
	// Turns an AVERROR code into the message FFmpeg has for it, so that a
	// failure to open reaches Java saying what libavformat actually objected
	// to instead of a bare number.
	std::string ErrorMessage(int error)
	{
		char buffer[AV_ERROR_MAX_STRING_SIZE] = { 0 };

		if (av_strerror(error, buffer, sizeof(buffer)) < 0) {
			return "Unknown FFmpeg error " + std::to_string(error);
		}

		return buffer;
	}

	void ThrowIOException(JNIEnv * env, const std::string & message)
	{
		jclass cls = env->FindClass("java/io/IOException");

		if (cls != nullptr) {
			env->ThrowNew(cls, message.c_str());
			env->DeleteLocalRef(cls);
		}
	}

	ffmpeg::MediaReader * ReaderOf(JNIEnv * env, jlong handle)
	{
		if (handle == 0) {
			jclass cls = env->FindClass("java/lang/IllegalStateException");

			if (cls != nullptr) {
				env->ThrowNew(cls, "MediaReader is closed");
				env->DeleteLocalRef(cls);
			}

			return nullptr;
		}

		return reinterpret_cast<ffmpeg::MediaReader *>(handle);
	}
}

JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_media_player_MediaReader_open
(JNIEnv * env, jclass caller, jstring source)
{
	if (source == nullptr) {
		ThrowIOException(env, "Source must not be null");

		return 0;
	}

	const char * chars = env->GetStringUTFChars(source, nullptr);

	if (chars == nullptr) {
		// The VM is out of memory and has already thrown.
		return 0;
	}

	std::string url(chars);

	env->ReleaseStringUTFChars(source, chars);

	auto reader = new ffmpeg::MediaReader();
	int result = reader->Open(url);

	if (result < 0) {
		delete reader;

		ThrowIOException(env, "Opening '" + url + "' failed: " + ErrorMessage(result));

		return 0;
	}

	return reinterpret_cast<jlong>(reader);
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_media_player_MediaReader_info
(JNIEnv * env, jclass caller, jlong handle)
{
	ffmpeg::MediaReader * reader = ReaderOf(env, handle);

	if (reader == nullptr) {
		return nullptr;
	}

	jclass cls = env->FindClass("dev/onvoid/webrtc/media/player/MediaInfo");

	if (cls == nullptr) {
		return nullptr;
	}

	jmethodID ctor = env->GetMethodID(cls, "<init>",
			"(JIIDLjava/lang/String;IILjava/lang/String;)V");

	if (ctor == nullptr) {
		env->DeleteLocalRef(cls);

		return nullptr;
	}

	// A stream that is not there is reported as a null codec name and zero
	// everything else, which is the contract MediaInfo documents.
	jstring videoCodec = reader->HasVideo()
			? env->NewStringUTF(reader->GetVideoCodecName()) : nullptr;
	jstring audioCodec = reader->HasAudio()
			? env->NewStringUTF(reader->GetAudioCodecName()) : nullptr;

	jobject info = env->NewObject(cls, ctor,
			static_cast<jlong>(reader->GetDurationUs()),
			static_cast<jint>(reader->GetVideoWidth()),
			static_cast<jint>(reader->GetVideoHeight()),
			static_cast<jdouble>(reader->GetFrameRate()),
			videoCodec,
			static_cast<jint>(reader->GetSampleRate()),
			static_cast<jint>(reader->GetChannels()),
			audioCodec);

	if (videoCodec != nullptr) {
		env->DeleteLocalRef(videoCodec);
	}
	if (audioCodec != nullptr) {
		env->DeleteLocalRef(audioCodec);
	}

	env->DeleteLocalRef(cls);

	return info;
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_player_MediaReader_dispose
(JNIEnv * env, jclass caller, jlong handle)
{
	// Closing twice is allowed, so a handle that is already zero is simply
	// nothing to do rather than an error.
	if (handle == 0) {
		return;
	}

	delete reinterpret_cast<ffmpeg::MediaReader *>(handle);
}
