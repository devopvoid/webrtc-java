#pragma once

#include <atomic>
#include <cstdint>
#include <string>

#include "api/video/adapted_video_track_source.h"
#include "api/video/video_frame.h"
#include "api/video/video_frame_buffer.h"
#include "rtc_base/ref_counted_object.h"

// A standalone video source that other components can feed with frames.
// Frames should be delivered as WebRTC VideoFrameBuffers (I420 preferred).
class FFmpegVideoSource final : public webrtc::AdaptedVideoTrackSource {
    public:
      FFmpegVideoSource()
        : webrtc::AdaptedVideoTrackSource(/*requires_screencast=*/false) {}

      // External push API (thread-safe to call from your pipeline’s sender thread).
      void DeliverFrame(const rtc::scoped_refptr<webrtc::VideoFrameBuffer>& buffer,
                        int64_t timestamp_ns) {
        // WebRTC timestamps are usually in us on VideoFrame; adapt as needed.
        webrtc::VideoFrame frame = webrtc::VideoFrame::Builder()
            .set_video_frame_buffer(buffer)
            .set_rotation(webrtc::kVideoRotation_0)
            .set_timestamp_us(timestamp_ns / 1000)
            .build();
        OnFrame(frame);
      }

      // Signal end-of-stream to consumers.
      void End() {
        ended_.store(true);
        // The state() override reads ended_.
      }

      // webrtc::MediaSourceInterface
      webrtc::MediaSourceInterface::SourceState state() const override {
        return ended_.load() ? webrtc::MediaSourceInterface::kEnded
                             : webrtc::MediaSourceInterface::kLive;
      }
      bool remote() const override { return false; }

    private:
      std::atomic<bool> ended_{false};
};
