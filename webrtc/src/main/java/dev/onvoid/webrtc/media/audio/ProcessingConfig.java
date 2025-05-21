package dev.onvoid.webrtc.media.audio;

import java.util.HashMap;
import java.util.Map;

public class ProcessingConfig {

    public Map<StreamName, AudioProcessingStreamConfig> streams;

    public ProcessingConfig() {
        this.streams = new HashMap<>();
    }

    public ProcessingConfig(Map<StreamName, AudioProcessingStreamConfig> streams) {
        this.streams = streams;
    }
}
