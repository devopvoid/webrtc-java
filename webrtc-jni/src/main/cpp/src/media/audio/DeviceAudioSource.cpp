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

#include "media/audio/DeviceAudioSource.h"

#include "api/audio/audio_view.h"
#include "audio/remix_resample.h"
#include "modules/audio_processing/include/audio_frame_proxies.h"
#include "rtc_base/logging.h"

#include <algorithm>
#include <utility>

namespace jni
{
	namespace
	{
		// The highest rate the recorded audio is processed at, the rate of the
		// Opus encoder. WebRTC chooses it from the encoders of the factory's
		// send streams, which a source does not know; this is their usual
		// maximum, and a sender resamples to its encoder's rate anyway.
		constexpr int kMaxProcessingRateHz = 48000;

		// Sets the format the recorded audio is processed in: the lowest
		// native rate of the AudioProcessing that keeps every frequency of the
		// input, as AudioTransportImpl chooses it, and the input's channels.
		void InitializeCaptureFrame(int inputRate, size_t inputChannels, webrtc::AudioFrame * frame)
		{
			const int minRate = std::min(inputRate, kMaxProcessingRateHz);

			for (int nativeRate : webrtc::AudioProcessing::kNativeSampleRatesHz) {
				frame->SetSampleRateAndChannelSize(nativeRate);

				if (nativeRate >= minRate) {
					break;
				}
			}

			frame->num_channels_ = inputChannels;
		}
	}

	// DeviceAudioCapture

	DeviceAudioCapture::DeviceAudioCapture(webrtc::scoped_refptr<webrtc::AudioDeviceModule> device) :
		device_(std::move(device))
	{
	}

	void DeviceAudioCapture::SetAudioProcessing(webrtc::scoped_refptr<webrtc::AudioProcessing> processing)
	{
		webrtc::MutexLock lock(&processMutex_);

		processing_ = std::move(processing);
	}

	void DeviceAudioCapture::SetWorkerThread(webrtc::Thread * workerThread)
	{
		webrtc::MutexLock lock(&controlMutex_);

		workerThread_ = workerThread;
	}

	void DeviceAudioCapture::SetRecordingRequested(bool requested)
	{
		recordingRequested_.store(requested);

		ApplyRecording();
	}

	bool DeviceAudioCapture::RecordingRequested() const
	{
		return recordingRequested_.load();
	}

	void DeviceAudioCapture::UpdateRecording()
	{
		{
			webrtc::MutexLock lock(&controlMutex_);

			if (workerThread_ == nullptr) {
				// No factory runs on the device: either none was created yet,
				// so no source can have a sink, or it is going away, and
				// Detach() stops what is left.
				return;
			}
			if (!workerThread_->IsCurrent()) {
				// Posted while the lock is held, so the thread cannot be
				// cleared and stopped in between. The task holds only a weak
				// reference: the sources may let this object go meanwhile.
				workerThread_->PostTask([weak = weak_from_this()]() {
					if (auto self = weak.lock()) {
						self->ApplyRecording();
					}
				});
				return;
			}
		}

		ApplyRecording();
	}

	void DeviceAudioCapture::ApplyRecording()
	{
		webrtc::scoped_refptr<webrtc::AudioDeviceModule> device;

		{
			webrtc::MutexLock lock(&controlMutex_);

			device = device_;
		}

		if (!device) {
			return;
		}

		const bool record = recordingRequested_.load() && HasSources();
		bool started = recordingStarted_.load();

		if (started && !device->Recording()) {
			// The application stopped the recording through its Java
			// AudioDeviceModule; start it again if it is still needed.
			recordingStarted_.store(false);
			started = false;
		}

		if (record && !started) {
			if (device->Recording()) {
				// The application started the recording through its Java
				// AudioDeviceModule. Its audio reaches the sources already, and
				// the recording stays the application's to stop.
				return;
			}
			if (device->InitRecording() != 0 || device->StartRecording() != 0) {
				RTC_LOG(LS_ERROR) << "DeviceAudioCapture: starting the recording failed";
				return;
			}

			recordingStarted_.store(true);

			RTC_LOG(LS_INFO) << "DeviceAudioCapture: recording started";
		}
		else if (!record && started) {
			device->StopRecording();

			recordingStarted_.store(false);

			RTC_LOG(LS_INFO) << "DeviceAudioCapture: recording stopped";
		}
	}

	bool DeviceAudioCapture::HasSources()
	{
		webrtc::MutexLock lock(&sourcesMutex_);

		return !sources_.empty();
	}

	void DeviceAudioCapture::Detach()
	{
		std::vector<webrtc::scoped_refptr<DeviceAudioSource>> sources;
		webrtc::scoped_refptr<webrtc::AudioDeviceModule> device;

		{
			webrtc::MutexLock lock(&controlMutex_);

			workerThread_ = nullptr;
			device = std::move(device_);
		}

		// The worker thread is gone, so nothing starts the device anymore. A
		// recording this object started would otherwise outlive the factory.
		if (device && recordingStarted_.exchange(false)) {
			device->StopRecording();
		}

		{
			webrtc::MutexLock lock(&processMutex_);

			processing_ = nullptr;
		}
		{
			webrtc::MutexLock lock(&sourcesMutex_);

			sources.swap(sources_);
		}

		// The sources are released here, outside the lock: one may be the last
		// reference, and its destructor does not call back into this object.
	}

	void DeviceAudioCapture::OnRecordedData(const void * audioSamples, size_t frames, size_t bytesPerSample,
											size_t channels, uint32_t sampleRate, uint32_t totalDelayMs,
											bool keyPressed, std::optional<int64_t> estimatedCaptureTimeNs)
	{
		// What AudioTransportImpl requires of recorded audio: 10 ms of 16-bit
		// mono or stereo, which fits into one audio frame.
		if (audioSamples == nullptr || channels < 1 || channels > 2 || bytesPerSample != 2 * channels ||
			sampleRate < 8000 || frames * 100 != sampleRate ||
			frames * channels > webrtc::AudioFrame::kMaxDataSizeSamples) {
			RTC_LOG(LS_WARNING) << "DeviceAudioCapture: dropping recorded audio of an unsupported format: "
								<< frames << " frames, " << channels << " channels, " << sampleRate << " Hz";
			return;
		}

		std::vector<webrtc::scoped_refptr<DeviceAudioSource>> sources;

		{
			webrtc::MutexLock lock(&sourcesMutex_);

			sources = sources_;
		}

		webrtc::MutexLock lock(&processMutex_);

		webrtc::InterleavedView<const int16_t> source(static_cast<const int16_t *>(audioSamples), frames, channels);

		frame_.Reset();
		InitializeCaptureFrame(static_cast<int>(sampleRate), channels, &frame_);
		webrtc::voe::RemixAndResample(source, static_cast<int>(sampleRate), &resampler_, &frame_);

		// Processed even without a source, as WebRTC does, so that the capture
		// post-processing, the voice activity detection among it, keeps seeing
		// a recording the application started itself.
		if (processing_) {
			processing_->set_stream_delay_ms(static_cast<int>(totalDelayMs));
			processing_->set_stream_key_pressed(keyPressed);

			int error = webrtc::ProcessAudioFrame(processing_.get(), &frame_);

			if (error != webrtc::AudioProcessing::kNoError) {
				RTC_LOG(LS_WARNING) << "DeviceAudioCapture: ProcessStream() failed: " << error;
			}
		}

		std::optional<int64_t> captureTimeMs;

		if (estimatedCaptureTimeNs) {
			captureTimeMs = *estimatedCaptureTimeNs / 1000000;
		}

		for (const auto & deviceSource : sources) {
			deviceSource->Deliver(frame_, captureTimeMs);
		}
	}

	void DeviceAudioCapture::AddSource(webrtc::scoped_refptr<DeviceAudioSource> source)
	{
		webrtc::MutexLock lock(&sourcesMutex_);

		sources_.push_back(std::move(source));
	}

	webrtc::scoped_refptr<DeviceAudioSource> DeviceAudioCapture::RemoveSource(DeviceAudioSource * source)
	{
		webrtc::MutexLock lock(&sourcesMutex_);

		auto it = std::find_if(sources_.begin(), sources_.end(), [source](const auto & registered) {
			return registered.get() == source;
		});

		if (it == sources_.end()) {
			return nullptr;
		}

		webrtc::scoped_refptr<DeviceAudioSource> removed = std::move(*it);
		sources_.erase(it);

		return removed;
	}

	// DeviceAudioSource

	DeviceAudioSource::DeviceAudioSource(const webrtc::AudioOptions & options,
										 std::shared_ptr<DeviceAudioCapture> capture) :
		options_(options),
		capture_(std::move(capture))
	{
	}

	DeviceAudioSource::~DeviceAudioSource()
	{
		// A source with sinks is held by the capture, so it is destroyed only
		// once its last sink is gone and the capture has let it go.
	}

	webrtc::MediaSourceInterface::SourceState DeviceAudioSource::state() const
	{
		return kLive;
	}

	bool DeviceAudioSource::remote() const
	{
		return false;
	}

	const webrtc::AudioOptions DeviceAudioSource::options() const
	{
		return options_;
	}

	void DeviceAudioSource::AddSink(webrtc::AudioTrackSinkInterface * sink)
	{
		bool registered = false;

		AddSinkLocked(sink, registered);

		// Outside the lock: stopping the device joins its recording thread,
		// which may be waiting for this lock to deliver audio.
		if (registered) {
			capture_->UpdateRecording();
		}
	}

	void DeviceAudioSource::AddSinkLocked(webrtc::AudioTrackSinkInterface * sink, bool & registered)
	{
		webrtc::MutexLock lock(&mutex_);

		if (sink == nullptr || std::find(sinks_.begin(), sinks_.end(), sink) != sinks_.end()) {
			return;
		}

		sinks_.push_back(sink);

		// Registered while this lock is held, so that registering and
		// unregistering follow the order of the sinks. The capture never takes
		// this lock while holding its own.
		if (sinks_.size() == 1) {
			capture_->AddSource(webrtc::scoped_refptr<DeviceAudioSource>(this));
			registered = true;
		}
	}

	void DeviceAudioSource::RemoveSink(webrtc::AudioTrackSinkInterface * sink)
	{
		// The capture's reference is released after the lock: it is never the
		// last one, since the caller holds the track, but should it be, this
		// object must not be destroyed while its own lock is held.
		webrtc::scoped_refptr<DeviceAudioSource> released;

		{
			webrtc::MutexLock lock(&mutex_);

			auto it = std::find(sinks_.begin(), sinks_.end(), sink);

			if (it == sinks_.end()) {
				return;
			}

			sinks_.erase(it);

			if (sinks_.empty()) {
				released = capture_->RemoveSource(this);
			}
		}

		// Outside the lock, see AddSink().
		if (released) {
			capture_->UpdateRecording();
		}
	}

	void DeviceAudioSource::Deliver(const webrtc::AudioFrame & frame, std::optional<int64_t> absoluteCaptureTimeMs)
	{
		webrtc::MutexLock lock(&mutex_);

		for (auto * sink : sinks_) {
			sink->OnData(frame.data(), 16, frame.sample_rate_hz_, frame.num_channels_,
						 frame.samples_per_channel_, absoluteCaptureTimeMs);
		}
	}
}
