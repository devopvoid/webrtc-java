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

#ifndef JNI_WEBRTC_MEDIA_AUDIO_VOICE_ACTIVITY_SINK_H_
#define JNI_WEBRTC_MEDIA_AUDIO_VOICE_ACTIVITY_SINK_H_

#include "media/audio/VoiceActivityListener.h"
#include "media/audio/VoiceDetector.h"

#include "api/media_stream_interface.h"

#include <memory>

namespace jni
{
	// A sink of an audio track that detects speech in what the source of the
	// track hands to sinks, and tells a listener when it begins and ends.
	class VoiceActivitySink : public webrtc::AudioTrackSinkInterface
	{
		public:
			explicit VoiceActivitySink(std::unique_ptr<VoiceActivityListener> listener);
			~VoiceActivitySink() = default;

			// AudioTrackSinkInterface implementation. The other variants
			// forward to this one.
			using webrtc::AudioTrackSinkInterface::OnData;
			void OnData(const void * data, int bitsPerSample, int sampleRate, size_t channels, size_t frames) override;

		private:
			VoiceDetector detector;

			const std::unique_ptr<VoiceActivityListener> listener;
	};
}

#endif
