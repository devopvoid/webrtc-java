//
// Created by kalgecin on 10/25/23.
//

#include "video/CustomVideoSource.h"
#include "rtc_base/logging.h"

namespace jni {
    void CustomVideoSource::OnFrameCaptured(const webrtc::VideoFrame& frame) {
        RTC_LOG(LS_VERBOSE) << "VideoFrame: " << frame.width() << "x" << frame.height() << " timestamp: " << frame.timestamp_us();
        OnFrame(frame);
    }

    webrtc::MediaSourceInterface::SourceState CustomVideoSource::state() const {
        return kLive;
    }

    bool CustomVideoSource::remote() const {
        return false;
    }

    bool CustomVideoSource::is_screencast() const {
        return false;
    }

    absl::optional<bool> CustomVideoSource::needs_denoising() const {
        return false;
    }
}