package dev.onvoid.webrtc.media.video;

public class CustomVideoSource extends VideoTrackSource {
    public CustomVideoSource() {
        super();
        initialize();
    }

    public native void OnFrameCaptured(VideoFrame frame);
    private native void initialize();
}
