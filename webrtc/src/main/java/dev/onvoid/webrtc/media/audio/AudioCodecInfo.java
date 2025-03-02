package dev.onvoid.webrtc.media.audio;

public class AudioCodecInfo {

    public int sampleRateHz;
    public int numChannels;
    public int defaultBitrateBps;
    public int minBitrateBps;
    public int maxBitrateBps;

    boolean allowComfortNoise;
    boolean supportsNetworkAdaption;

    public AudioCodecInfo(){
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
}
