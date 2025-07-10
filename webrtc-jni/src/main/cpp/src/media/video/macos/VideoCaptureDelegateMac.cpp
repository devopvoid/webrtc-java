#import "media/video/macos/VideoCaptureMac.h"
#import "media/video/macos/VideoCaptureDelegateMac.h"
#import "sdk/objc/base/RTCVideoFrame.h"
#import "sdk/objc/native/src/objc_frame_buffer.h"
#import "api/video/video_frame.h"
#import "rtc_base/timestamp_aligner.h"

@implementation VideoCaptureDelegateMac {
    JNIVideoCaptureMac * jniHandler;
    webrtc::TimestampAligner timestamp_aligner;
}

- (instancetype)initWithHandler:(JNIVideoCaptureMac *)handler {
    if (self = [super init]) {
        jniHandler = handler;
    }
    return self;
}

- (void)capturer:(RTCVideoCapturer *)capturer didCaptureVideoFrame:(RTCVideoFrame *)frame {
    const int64_t timestamp_us = frame.timeStampNs / webrtc::kNumNanosecsPerMicrosec;
    const int64_t translated_timestamp_us = timestamp_aligner.TranslateTimestamp(timestamp_us, webrtc::TimeMicros());

    webrtc::scoped_refptr<webrtc::VideoFrameBuffer> buffer = webrtc::make_ref_counted<webrtc::ObjCFrameBuffer>(frame.buffer);

    jniHandler->OnFrame(webrtc::VideoFrame::Builder()
                                 .set_video_frame_buffer(buffer)
                                 .set_rotation(webrtc::kVideoRotation_0)
                                 .set_timestamp_us(translated_timestamp_us)
                                 .build());
}
@end
