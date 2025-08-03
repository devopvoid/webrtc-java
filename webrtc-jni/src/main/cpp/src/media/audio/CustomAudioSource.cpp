#include "media/audio/CustomAudioSource.h"

#include <algorithm>
#include <absl/types/optional.h>

namespace jni
{
    CustomAudioSource::CustomAudioSource(std::shared_ptr<SyncClock> clock) :
        clock_(clock),
        total_samples_captured_(0),
        audio_capture_delay_us_(0)
    {
    }

    void CustomAudioSource::RegisterObserver(webrtc::ObserverInterface * observer)
    {
        // Not implemented - not needed for custom sources
    }

    void CustomAudioSource::UnregisterObserver(webrtc::ObserverInterface * observer)
    {
        // Not implemented - not needed for custom sources
    }

    void CustomAudioSource::AddSink(webrtc::AudioTrackSinkInterface * sink)
    {
        //webrtc::CritScope lock(&crit_);

        sinks_.push_back(sink);
    }

    void CustomAudioSource::RemoveSink(webrtc::AudioTrackSinkInterface * sink)
    {
        //webrtc::CritScope lock(&crit_);

        sinks_.erase(std::remove(sinks_.begin(), sinks_.end(), sink), sinks_.end());
    }

    webrtc::AudioSourceInterface::SourceState CustomAudioSource::state() const
    {
        return kLive;
    }

    bool CustomAudioSource::remote() const
    {
        return false;
    }

    void CustomAudioSource::PushAudioData(const void * audio_data, int bits_per_sample,
                                         int sample_rate, size_t number_of_channels,
                                         size_t number_of_frames)
    {
        //webrtc::CritScope lock(&crit_);

        // Calculate absolute capture time
        int64_t timestamp_us = clock_->GetTimestampUs();

        // Apply delay if audio capture has inherent latency
        timestamp_us -= audio_capture_delay_us_;

        // Calculate NTP time for this audio frame
        int64_t ntp_time_ms = clock_->GetNtpTime().ToMs();

        // Create absolute capture time
        absl::optional<int64_t> absolute_capture_time_ms = timestamp_us / 1000;

        // Send to all sinks with timing information
        for (auto * sink : sinks_) {
            sink->OnData(audio_data, bits_per_sample, sample_rate,
                        number_of_channels, number_of_frames,
                        absolute_capture_time_ms);
        }

        // Update total samples for tracking
        total_samples_captured_ += number_of_frames;
    }

    void CustomAudioSource::SetAudioCaptureDelay(int64_t delay_us)
    {
        audio_capture_delay_us_ = delay_us;
    }
}