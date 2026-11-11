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

#include "JNI_DecoderFault.h"
#include "media/DecoderFault.h"
#include "media/JniBoundary.h"
#include "media/MediaPlayer.h"

namespace
{
	class CountingObserver : public ffmpeg::MediaPlayerObserver
	{
		public:
			void OnThreadStart() override { ++starts; }
			void OnThreadStop() override { ++stops; }
			void OnStateChanged(int state) override
			{
				ordered = ordered && starts == 1 && stops == 0;
				last_state = state;
			}
			void OnEndOfStream() override {}
			void OnError(const std::string &) override {}
			int starts = 0;
			int stops = 0;
			int last_state = ffmpeg::kIdle;
			bool ordered = true;
	};
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_player_DecoderFault_arm
(JNIEnv *, jclass, jboolean video, jint mode)
{
	ffmpeg::DecoderFault::Arm(video == JNI_TRUE, mode);
}

JNIEXPORT void JNICALL Java_dev_onvoid_webrtc_media_player_DecoderFault_disarm
(JNIEnv *, jclass)
{
	ffmpeg::DecoderFault::Disarm();
}

JNIEXPORT jint JNICALL Java_dev_onvoid_webrtc_media_player_DecoderFault_retries
(JNIEnv *, jclass)
{
	return ffmpeg::DecoderFault::Retries();
}

JNIEXPORT jboolean JNICALL Java_dev_onvoid_webrtc_media_player_DecoderFault_observerLifecycle
(JNIEnv * env, jclass, jstring source, jlong api)
{
	return ffmpeg::CallFromJava<jboolean>(env, JNI_FALSE, [&]() -> jboolean {
		ffmpeg::JavaStringChars chars(env, source);
		if (chars.get() == nullptr) {
			return JNI_FALSE;
		}
		auto reader = std::make_unique<ffmpeg::MediaReader>();
		if (reader->Open(chars.utf8()) < 0) {
			return JNI_FALSE;
		}
		// Native observers have no JVM detach flag to hide a double stop.
		ffmpeg::MediaPlayer player(std::move(reader),
				reinterpret_cast<const webrtc_java_api *>(api), nullptr, nullptr);
		auto observer = std::make_unique<CountingObserver>();
		auto * counts = observer.get();
		player.SetObserver(std::move(observer));
		if (player.Initialize() < 0) {
			return JNI_FALSE;
		}
		player.Close();
		player.Close();
		return counts->starts == 1 && counts->stops == 1 && counts->ordered
				&& counts->last_state == ffmpeg::kClosed ? JNI_TRUE : JNI_FALSE;
	});
}
