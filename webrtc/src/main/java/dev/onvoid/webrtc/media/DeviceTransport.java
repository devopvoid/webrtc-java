package dev.onvoid.webrtc.media;

public enum DeviceTransport {
    UNKNOWN,
    HDMI,
    USB,
    WIRELESS;

    @Override
    public String toString() {
        switch (this) {
            case HDMI:
                return "HDMI";
            case USB:
                return "USB";
            case WIRELESS:
                return "WIRELESS";
            default:
                return "UNKNOWN";
        }
    }
}
