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
#include "api/audio/audio_device.h"
#include "api/notifier.h"
#include "api/scoped_refptr.h"
#include "common_audio/resampler/include/push_resampler.h"
#include "rtc_base/synchronization/mutex.h"
#include "rtc_base/thread.h"
#include "rtc_base/thread_annotations.h"

#include <atomic>
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
	// It also decides when the device records: while a source has a sink and
	// WebRTC asks for a recording, which it does while any send stream of the
	// factory runs and the application has not switched recording off. A
	// factory that sends only pushed or forwarded audio therefore never opens
	// the recording device. The device is started and stopped on the factory's
	// worker thread, where WebRTC asks for it; a change of the sinks posts the
	// update there rather than waiting for it, so that no lock is held while
	// the device's recording thread is joined.
	//
	// Shared by the factory's ProxyAudioDeviceModule, which feeds it on the
	// recording thread, and by every DeviceAudioSource it created, which may
	// outlive the module.
	class DeviceAudioCapture : public std::enable_shared_from_this<DeviceAudioCapture>
	{
		public:
			explicit DeviceAudioCapture(webrtc::scoped_refptr<webrtc::AudioDeviceModule> device);
			DeviceAudioCapture(const DeviceAudioCapture &) = delete;
			DeviceAudioCapture & operator=(const DeviceAudioCapture &) = delete;

			// Sets the AudioProcessing the factory was built with. Called once,
			// before the factory exists.
			void SetAudioProcessing(webrtc::scoped_refptr<webrtc::AudioProcessing> processing);

			// Sets the factory's worker thread, while WebRTC's transport is
			// registered with the device, and clears it when the transport is
			// unregistered, before the thread is stopped. Called on that thread.
			void SetWorkerThread(webrtc::Thread * workerThread);

			// Records whether WebRTC asks the device to record. Called on the
			// worker thread.
			void SetRecordingRequested(bool requested);

			// Whether WebRTC asks the device to record, which is what WebRTC
			// is told when it asks whether the device records, whatever the
			// sources: it decides from that when to ask again.
			bool RecordingRequested() const;

			// Stops a recording this object started, and releases the
			// AudioProcessing, the device and every source, once the module is
			// gone, its worker thread with it, and no more audio can arrive.
			void Detach();

			// Called on the recording thread with 10 ms of interleaved 16-bit
			// audio. Processes it and hands it to the sources that have sinks.
			void OnRecordedData(const void * audioSamples, size_t frames, size_t bytesPerSample,
								size_t channels, uint32_t sampleRate, uint32_t totalDelayMs,
								bool keyPressed, std::optional<int64_t> estimatedCaptureTimeNs);

			// A source is registered while it has at least one sink, and held
			// for that long, so it is alive whenever audio is handed to it.
			// Neither call starts or stops the device: once its lock is
			// released, the source calls UpdateRecording().
			void AddSource(webrtc::scoped_refptr<DeviceAudioSource> source);
			webrtc::scoped_refptr<DeviceAudioSource> RemoveSource(DeviceAudioSource * source);

			// Brings the device's recording in line with the sources and with
			// WebRTC's request: at once on the worker thread, else posted to it.
			// Must not be called while a source's lock is held.
			void UpdateRecording();

		private:
			// Starts or stops the device. Runs on the worker thread.
			void ApplyRecording();

			bool HasSources();

			// Guards the device and the worker thread.
			webrtc::Mutex controlMutex_;
			webrtc::scoped_refptr<webrtc::AudioDeviceModule> device_ RTC_GUARDED_BY(controlMutex_);
			webrtc::Thread * workerThread_ RTC_GUARDED_BY(controlMutex_) = nullptr;

			// Set on the worker thread; read by Detach() once it is gone.
			std::atomic<bool> recordingRequested_ { false };
			std::atomic<bool> recordingStarted_ { false };

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
			// Adds the sink and registers this source with the capture if it
			// is the first one, under the lock.
			void AddSinkLocked(webrtc::AudioTrackSinkInterface * sink, bool & registered);

			const webrtc::AudioOptions options_;
			const std::shared_ptr<DeviceAudioCapture> capture_;

			// Held for a whole delivery, as CustomAudioSource does, so that a
			// sink is not called once RemoveSink() has returned.
			webrtc::Mutex mutex_;
			std::vector<webrtc::AudioTrackSinkInterface *> sinks_ RTC_GUARDED_BY(mutex_);
	};
}

#endif
