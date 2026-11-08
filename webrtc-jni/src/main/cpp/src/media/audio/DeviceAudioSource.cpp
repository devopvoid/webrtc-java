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

	void DeviceAudioCapture::SetAudioProcessing(webrtc::scoped_refptr<webrtc::AudioProcessing> processing)
	{
		webrtc::MutexLock lock(&processMutex_);

		processing_ = std::move(processing);
	}

	void DeviceAudioCapture::Detach()
	{
		std::vector<webrtc::scoped_refptr<DeviceAudioSource>> sources;

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
