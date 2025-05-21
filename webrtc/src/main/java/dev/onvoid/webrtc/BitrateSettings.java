package dev.onvoid.webrtc;

public class BitrateSettings {

    public static final int kDefaultStartBitrateBps = 300000;

    public int minBitrateBps;
    public int startBitrateBps;
    public int maxBitrateBps;

    public BitrateSettings() {
        this.minBitrateBps = 0;
        this.startBitrateBps = kDefaultStartBitrateBps;
        this.maxBitrateBps = -1;
    }
}
