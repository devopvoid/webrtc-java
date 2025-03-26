//
// Created by kalgecin on 10/25/23.
//

#ifndef WEBRTC_JAVA_CUSTOMVIDEOSOURCE_H
#define WEBRTC_JAVA_CUSTOMVIDEOSOURCE_H

#include <media/base/adapted_video_track_source.h>

namespace jni {
    class CustomVideoSource : public rtc::AdaptedVideoTrackSource {
    public:
        void OnFrameCaptured(const webrtc::VideoFrame &frame);

        bool remote() const override;

        SourceState state() const override;

        bool is_screencast() const override;

        absl::optional<bool> needs_denoising() const override;
    };
}
#endif //WEBRTC_JAVA_CUSTOMVIDEOSOURCE_H
