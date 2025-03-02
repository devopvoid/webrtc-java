package dev.onvoid.webrtc.media.audio;

import java.util.Map;

public class SdpAudioFormat {

    public String name;
    public int clockRateHz;
    public int numChannels;
    public Map<String,String> parameters;

    public SdpAudioFormat(){

    }


    public String info() {
        return "SdpAudioFormat{" +
                "name='" + name + '\'' +
                ", clockRateHz=" + clockRateHz +
                ", numChannels=" + numChannels +
                ", parameters=" + parameters +
                '}';
    }
}
