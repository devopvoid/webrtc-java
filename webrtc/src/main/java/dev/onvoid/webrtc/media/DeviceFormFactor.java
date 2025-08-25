package dev.onvoid.webrtc.media;

public enum DeviceFormFactor {
    UNKNOWN,
    SPEAKER,
    MICROPHONE,
    HEADSET,
    HEADPHONE;

    @Override
    public String toString() {
        switch (this) {
            case SPEAKER:
                return "SPEAKER";
            case MICROPHONE:
                return "MICROPHONE";
            case HEADSET:
                return "HEADSET";
            case HEADPHONE:
                return "HEADPHONE";
            default:
                return "UNKNOWN";
        }
    }
}
