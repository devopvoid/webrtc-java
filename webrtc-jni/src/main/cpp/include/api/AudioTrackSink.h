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

#ifndef JNI_WEBRTC_API_AUDIO_TRACK_SINK_H_
#define JNI_WEBRTC_API_AUDIO_TRACK_SINK_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include "api/media_stream_interface.h"
#include "api/rtp_packet_infos.h"

#include <jni.h>
#include <memory>
#include <optional>

namespace jni
{
	class AudioTrackSink : public webrtc::AudioTrackSinkInterface
	{
		public:
			// With packetInfos, the Java sink is called with the RTP packets
			// the audio was assembled from.
			AudioTrackSink(JNIEnv * env, const JavaGlobalRef<jobject> & sink, bool packetInfos);
			~AudioTrackSink() = default;

			// AudioTrackSinkInterface implementation. WebRTC calls the variant
			// its source knows the most for.
			void OnData(const void * data, int bitsPerSample, int sampleRate, size_t channels, size_t frames) override;
			void OnData(const void * data, int bitsPerSample, int sampleRate, size_t channels, size_t frames,
				std::optional<int64_t> absoluteCaptureTimestampMs) override;
			void OnData(const void * data, int bitsPerSample, int sampleRate, size_t channels, size_t frames,
				std::optional<int64_t> absoluteCaptureTimestampMs, const webrtc::RtpPacketInfos & packetInfos) override;

		private:
			void Deliver(const void * data, int bitsPerSample, int sampleRate, size_t channels, size_t frames,
				const webrtc::RtpPacketInfos * infos);

			class JavaAudioTrackSinkClass : public JavaClass
			{
				public:
					explicit JavaAudioTrackSinkClass(JNIEnv * env);

					jmethodID onData;
					jmethodID onDataWithPacketInfos;
			};

		private:
			JavaGlobalRef<jobject> sink;

			const bool packetInfos;

			const std::shared_ptr<JavaAudioTrackSinkClass> javaClass;
	};
}

#endif