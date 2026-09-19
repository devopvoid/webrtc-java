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

#include "media/video/CustomVideoSource.h"

#include "rtc_base/time_utils.h"

namespace jni
{
    CustomVideoSource::CustomVideoSource(std::shared_ptr<SyncClock> clock) :
        clock_(clock),
        frame_id_(0)
    {
    }

    bool CustomVideoSource::is_screencast() const
    {
		return false;
	}

	std::optional<bool> CustomVideoSource::needs_denoising() const
	{
		return false;
	}

	webrtc::MediaSourceInterface::SourceState CustomVideoSource::state() const
	{
		return kLive;
	}

	bool CustomVideoSource::remote() const
	{
		return false;
	}

    void CustomVideoSource::PushFrame(const webrtc::VideoFrame& frame)
    {
        // No capture time from the caller, so the frame is captured now.
        DeliverFrame(frame, clock_->GetTimestampUs(), clock_->GetNtpTime().ToMs());
    }

    void CustomVideoSource::PushFrame(const webrtc::VideoFrame& frame, int64_t timestamp_us)
    {
        // The caller's capture time is on the same clock as TimeMicros(), so
        // the frame's age gives its NTP capture time. That is the field the
        // encoder turns into the outgoing RTP timestamp, which is why a
        // caller's timing reaches the wire at all.
        int64_t age_ms = (webrtc::TimeMicros() - timestamp_us) / webrtc::kNumMicrosecsPerMillisec;

        DeliverFrame(frame, timestamp_us, clock_->GetNtpTime().ToMs() - age_ms);
    }

    void CustomVideoSource::DeliverFrame(const webrtc::VideoFrame& frame, int64_t timestamp_us,
                                         int64_t ntp_time_ms)
    {
        // Create frame with proper timestamp
        webrtc::VideoFrame timestamped_frame = frame;

        timestamped_frame.set_timestamp_us(timestamp_us);

        // Set RTP timestamp (90kHz clock). The encoder recomputes this from
        // the NTP capture time below before sending, so it only matters to a
        // sink that reads the frame before it is encoded.
        uint32_t rtp_timestamp = static_cast<uint32_t>((timestamp_us * 90) / 1000);
        timestamped_frame.set_rtp_timestamp(rtp_timestamp);

        // Set NTP time for synchronization
        timestamped_frame.set_ntp_time_ms(ntp_time_ms);

        // Increment frame ID
        timestamped_frame.set_id(frame_id_++);

        OnFrame(timestamped_frame);
    }
}