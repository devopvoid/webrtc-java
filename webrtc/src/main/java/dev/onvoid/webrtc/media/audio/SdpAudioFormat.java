package dev.onvoid.webrtc.media.audio;

import java.util.Map;
import java.util.Objects;

public class SdpAudioFormat {

    public String name;
    public int clockRateHz;
    public int numChannels;
    public Map<String, String> parameters;

    public SdpAudioFormat() {

    }


    public String info() {
        return "SdpAudioFormat{" +
                "name='" + name + '\'' +
                ", clockRateHz=" + clockRateHz +
                ", numChannels=" + numChannels +
                ", parameters=" + parameters +
                '}';
    }

    @Override
    public String toString() {
        return info();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        SdpAudioFormat that = (SdpAudioFormat) o;
        return clockRateHz == that.clockRateHz && numChannels == that.numChannels && Objects.equals(name, that.name) && Objects.equals(parameters, that.parameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, clockRateHz, numChannels, parameters);
    }
}
