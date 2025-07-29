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
        // Create frame with proper timestamp
        webrtc::VideoFrame timestamped_frame = frame;

        // Use synchronized clock for timestamp
        int64_t timestamp_us = clock_->GetTimestampUs();
        timestamped_frame.set_timestamp_us(timestamp_us);

        // Set RTP timestamp (90kHz clock)
        uint32_t rtp_timestamp = static_cast<uint32_t>((timestamp_us * 90) / 1000);
        timestamped_frame.set_rtp_timestamp(rtp_timestamp);

        // Set NTP time for synchronization
        timestamped_frame.set_ntp_time_ms(clock_->GetNtpTime().ToMs());

        // Increment frame ID
        timestamped_frame.set_id(frame_id_++);

        OnFrame(timestamped_frame);
    }
}