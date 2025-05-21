package dev.onvoid.webrtc.media.audio;

import java.util.Objects;

public class AudioCodecInfo {

    public int sampleRateHz;
    public int numChannels;
    public int defaultBitrateBps;
    public int minBitrateBps;
    public int maxBitrateBps;

    boolean allowComfortNoise;
    boolean supportsNetworkAdaption;

    public AudioCodecInfo() {
        this.allowComfortNoise = true;
        supportsNetworkAdaption = false;
    }

    public String info() {
        return "AudioCodecInfo{" +
                "sampleRateHz=" + sampleRateHz +
                ", numChannels=" + numChannels +
                ", defaultBitrateBps=" + defaultBitrateBps +
                ", minBitrateBps=" + minBitrateBps +
                ", maxBitrateBps=" + maxBitrateBps +
                ", allowComfortNoise=" + allowComfortNoise +
                ", supportsNetworkAdaption=" + supportsNetworkAdaption +
                '}';
    }

    @Override
    public String toString() {
        return info();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        AudioCodecInfo that = (AudioCodecInfo) o;
        return sampleRateHz == that.sampleRateHz && numChannels == that.numChannels && defaultBitrateBps == that.defaultBitrateBps && minBitrateBps == that.minBitrateBps && maxBitrateBps == that.maxBitrateBps && allowComfortNoise == that.allowComfortNoise && supportsNetworkAdaption == that.supportsNetworkAdaption;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sampleRateHz, numChannels, defaultBitrateBps, minBitrateBps, maxBitrateBps, allowComfortNoise, supportsNetworkAdaption);
    }
}
