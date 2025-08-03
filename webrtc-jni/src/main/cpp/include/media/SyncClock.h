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

#ifndef JNI_WEBRTC_MEDIA_SYNC_CLOCK_H_
#define JNI_WEBRTC_MEDIA_SYNC_CLOCK_H_

#include "system_wrappers/include/clock.h"
#include "system_wrappers/include/ntp_time.h"

#include <chrono>

namespace jni
{
    // Synchronized Clock for A/V timing
    class SyncClock
    {
        public:
            SyncClock();

            // Get current timestamp in microseconds
            int64_t GetTimestampUs() const;

            // Get current timestamp in milliseconds
            int64_t GetTimestampMs() const;

            // Get NTP timestamp for RTP synchronization
            webrtc::NtpTime GetNtpTime() const;

        private:
            std::chrono::steady_clock::time_point start_time_;
    };
}
#endif