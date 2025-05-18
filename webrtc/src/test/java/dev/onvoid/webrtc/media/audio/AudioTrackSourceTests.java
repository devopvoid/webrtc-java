package dev.onvoid.webrtc.media.audio;

import dev.onvoid.webrtc.TestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class AudioTrackSourceTests extends TestBase {

    private AudioTrackSource audioSource;

    @BeforeEach
    void init() {
        AudioOptions audioOptions = new AudioOptions();
        this.audioSource = factory.createAudioSource(audioOptions);
    }

    @AfterEach
    void dispose() {
        audioSource.dispose();
    }


    @Test
    void addNullSink() {
        assertThrows(NullPointerException.class, () -> audioSource.addSink(null));
    }

    @Test
    void removeNullSink() {
        assertThrows(NullPointerException.class, () -> audioSource.removeSink(null));
    }

    @Test
    void addRemoveSink() {
        AudioTrackSink sink = (data, bitsPerSample, sampleRate, channels, frames) -> { };

        audioSource.addSink(sink);
        audioSource.removeSink(sink);
    }

    @Test
    void setVolume(){
        audioSource.setVolume(1.0);
    }
}
