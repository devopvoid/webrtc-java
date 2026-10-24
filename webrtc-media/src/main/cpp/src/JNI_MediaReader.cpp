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
#include "media/ErrorText.h"
#include "media/JniBoundary.h"
#include "media/MediaReader.h"

#include <memory>
#include <string>

extern "C" {
#include <libavutil/error.h>
}

namespace
{
	// The source as it may be shown: a URL loses the user name and password
	// it carries in front of its host, since error messages end up in logs.
	// Anything that is not a URL with credentials is returned as it is.
	std::string WithoutCredentials(const std::string & source)
	{
		const size_t scheme_end = source.find("://");

		if (scheme_end == std::string::npos) {
			return source;
		}

		const size_t authority_start = scheme_end + 3;
		const size_t authority_end = source.find_first_of("/?#", authority_start);
		const size_t at = source.rfind('@', authority_end == std::string::npos
				? std::string::npos : authority_end - 1);

		if (at == std::string::npos || at < authority_start) {
			return source;
		}

		return source.substr(0, authority_start) + source.substr(at + 1);
	}

	ffmpeg::MediaReader * ReaderOf(JNIEnv * env, jlong handle)
	{
		if (handle == 0) {
			ffmpeg::ThrowJavaException(env, "java/lang/IllegalStateException", "MediaReader is closed");

			return nullptr;
		}

		return reinterpret_cast<ffmpeg::MediaReader *>(handle);
	}
}

JNIEXPORT jlong JNICALL Java_dev_onvoid_webrtc_media_player_MediaReader_open
(JNIEnv * env, jclass caller, jstring source, jlong timeoutUs)
{
	return ffmpeg::CallFromJava<jlong>(env, 0, [&]() -> jlong {
		if (source == nullptr) {
			ffmpeg::ThrowIOException(env, "Source must not be null");

			return 0;
		}

		std::string url;

		{
			// Released before opening, which may wait for the network.
			ffmpeg::JavaStringChars chars(env, source);

			if (chars.get() == nullptr) {
				return 0;
			}

			url = chars.str();
		}

		auto reader = std::make_unique<ffmpeg::MediaReader>(static_cast<int64_t>(timeoutUs));
		int result = reader->Open(url);

		if (result < 0) {
			reader.reset();

			ffmpeg::ThrowIOException(env, "Opening '" + WithoutCredentials(url) + "' failed: " + ffmpeg::ErrorText(result));

			return 0;
		}

		return reinterpret_cast<jlong>(reader.release());
	});
}

JNIEXPORT jobject JNICALL Java_dev_onvoid_webrtc_media_player_MediaReader_info
(JNIEnv * env, jclass caller, jlong handle)
{
	return ffmpeg::CallFromJava<jobject>(env, nullptr, [&]() -> jobject {
		ffmpeg::MediaReader * reader = ReaderOf(env, handle);

		if (reader == nullptr) {
			return nullptr;
		}

		ffmpeg::LocalRef<jclass> cls(env, env->FindClass("dev/onvoid/webrtc/media/player/MediaInfo"));

		if (cls.get() == nullptr) {
			return nullptr;
		}

		jmethodID ctor = env->GetMethodID(cls.get(), "<init>",
				"(JIIDLjava/lang/String;IILjava/lang/String;)V");

		if (ctor == nullptr) {
			return nullptr;
		}

		// A stream that is not there is reported as a null codec name and zero
		// everything else, which is the contract MediaInfo documents. A name
		// the VM could not make has left an exception, and no JNI call may
		// follow but cleaning up.
		ffmpeg::LocalRef<jstring> videoCodec(env, reader->HasVideo()
				? env->NewStringUTF(reader->GetVideoCodecName()) : nullptr);

		if (reader->HasVideo() && videoCodec.get() == nullptr) {
			return nullptr;
		}

		ffmpeg::LocalRef<jstring> audioCodec(env, reader->HasAudio()
				? env->NewStringUTF(reader->GetAudioCodecName()) : nullptr);

		if (reader->HasAudio() && audioCodec.get() == nullptr) {
			return nullptr;
		}

		return env->NewObject(cls.get(), ctor,
				static_cast<jlong>(reader->GetDurationUs()),
				static_cast<jint>(reader->GetVideoWidth()),
				static_cast<jint>(reader->GetVideoHeight()),
				static_cast<jdouble>(reader->GetFrameRate()),
				videoCodec.get(),
				static_cast<jint>(reader->GetSampleRate()),
				static_cast<jint>(reader->GetChannels()),
				audioCodec.get());
	});
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
