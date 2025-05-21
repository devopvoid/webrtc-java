package dev.onvoid.webrtc.media.audio;

import dev.onvoid.webrtc.internal.NativeLoader;
import dev.onvoid.webrtc.internal.NativeObject;

import java.util.List;

public class AudioEncoderFactory extends NativeObject {

    static {
        try {
            NativeLoader.loadLibrary("webrtc-java");
        } catch (Exception e) {
            throw new RuntimeException("Load library 'webrtc-java' failed", e);
        }
    }

    public native List<AudioCodecSpec> getSupportedEncoders();

    public native AudioCodecInfo queryAudioEncoder(SdpAudioFormat format);

}
