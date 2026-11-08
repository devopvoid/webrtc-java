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

#ifndef JNI_WEBRTC_MEDIA_AUDIO_DEVICE_AUDIO_SOURCE_H_
#define JNI_WEBRTC_MEDIA_AUDIO_DEVICE_AUDIO_SOURCE_H_

#include "api/audio/audio_frame.h"
#include "api/audio/audio_processing.h"
#include "api/audio_options.h"
#include "api/media_stream_interface.h"
#include "api/notifier.h"
#include "api/scoped_refptr.h"
#include "common_audio/resampler/include/push_resampler.h"
#include "rtc_base/synchronization/mutex.h"
#include "rtc_base/thread_annotations.h"

#include <cstddef>
#include <cstdint>
#include <memory>
#include <optional>
#include <vector>

namespace jni
{
	class DeviceAudioSource;

	// Hands the audio recorded by a factory's audio device module to the
	// factory's DeviceAudioSources, after running it through the factory's
	// AudioProcessing.
	//
	// WebRTC would otherwise process the recorded audio itself and feed it into
	// every audio send stream of the factory, whatever the stream's track. Here
	// it reaches only the senders of device-captured tracks, through the tracks'
	// sinks, like the audio of every other kind of local source. The playout
	// side is left to WebRTC, which feeds the same AudioProcessing the render
	// signal, so echo cancellation sees the audio it has to remove.
	//
	// Shared by the factory's ProxyAudioDeviceModule, which feeds it on the
	// recording thread, and by every DeviceAudioSource it created, which may
	// outlive the module.
	class DeviceAudioCapture
	{
		public:
			DeviceAudioCapture() = default;
			DeviceAudioCapture(const DeviceAudioCapture &) = delete;
			DeviceAudioCapture & operator=(const DeviceAudioCapture &) = delete;

			// Sets the AudioProcessing the factory was built with. Called once,
			// before the factory exists.
			void SetAudioProcessing(webrtc::scoped_refptr<webrtc::AudioProcessing> processing);

			// Releases the AudioProcessing and every source, once the module is
			// gone and no more audio can arrive.
			void Detach();

			// Called on the recording thread with 10 ms of interleaved 16-bit
			// audio. Processes it and hands it to the sources that have sinks.
			void OnRecordedData(const void * audioSamples, size_t frames, size_t bytesPerSample,
								size_t channels, uint32_t sampleRate, uint32_t totalDelayMs,
								bool keyPressed, std::optional<int64_t> estimatedCaptureTimeNs);

			// A source is registered while it has at least one sink, and held
			// for that long, so it is alive whenever audio is handed to it.
			void AddSource(webrtc::scoped_refptr<DeviceAudioSource> source);
			webrtc::scoped_refptr<DeviceAudioSource> RemoveSource(DeviceAudioSource * source);

		private:
			// Guards the processing state, which only the recording thread
			// uses; it changes threads when the device is restarted.
			webrtc::Mutex processMutex_;
			webrtc::scoped_refptr<webrtc::AudioProcessing> processing_ RTC_GUARDED_BY(processMutex_);
			webrtc::AudioFrame frame_ RTC_GUARDED_BY(processMutex_);
			webrtc::PushResampler<int16_t> resampler_ RTC_GUARDED_BY(processMutex_);

			// Not held while the sources deliver: the list is copied first, so
			// adding or removing a sink never waits for another source's sinks.
			webrtc::Mutex sourcesMutex_;
			std::vector<webrtc::scoped_refptr<DeviceAudioSource>> sources_ RTC_GUARDED_BY(sourcesMutex_);
	};

	// The source of a track that sends audio captured by the factory's audio
	// device module, as PeerConnectionFactory.createAudioSource() creates it.
	// Its sinks, the senders of its tracks among them, receive the recorded
	// audio after the factory's AudioProcessing, on the recording thread.
	class DeviceAudioSource : public webrtc::Notifier<webrtc::AudioSourceInterface>
	{
		public:
			DeviceAudioSource(const webrtc::AudioOptions & options, std::shared_ptr<DeviceAudioCapture> capture);
			~DeviceAudioSource() override;

			// webrtc::MediaSourceInterface implementation.
			SourceState state() const override;
			bool remote() const override;

			// webrtc::AudioSourceInterface implementation. WebRTC applies the
			// options to the factory's AudioProcessing when a sender starts.
			const webrtc::AudioOptions options() const override;
			void AddSink(webrtc::AudioTrackSinkInterface * sink) override;
			void RemoveSink(webrtc::AudioTrackSinkInterface * sink) override;

			// Hands one processed frame to every sink.
			void Deliver(const webrtc::AudioFrame & frame, std::optional<int64_t> absoluteCaptureTimeMs);

		private:
			const webrtc::AudioOptions options_;
			const std::shared_ptr<DeviceAudioCapture> capture_;

			// Held for a whole delivery, as CustomAudioSource does, so that a
			// sink is not called once RemoveSink() has returned.
			webrtc::Mutex mutex_;
			std::vector<webrtc::AudioTrackSinkInterface *> sinks_ RTC_GUARDED_BY(mutex_);
	};
}

#endif
