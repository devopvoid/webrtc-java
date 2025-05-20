package dev.onvoid.webrtc.media.audio;

import java.util.Objects;

public class AudioCodecSpec {

    public SdpAudioFormat format;
    public AudioCodecInfo info;

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        AudioCodecSpec that = (AudioCodecSpec) o;
        return Objects.equals(format, that.format) && Objects.equals(info, that.info);
    }

    @Override
    public int hashCode() {
        return Objects.hash(format, info);
    }

    @Override
    public String toString() {
        return "AudioCodecSpec{" +
                "format=" + format +
                ", info=" + info +
                '}';
    }
}
