package dev.onvoid.webrtc.media.audio;

import dev.onvoid.webrtc.media.MediaDevices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

class AudioDeviceModuleTest {

    private AudioDeviceModule module;


    @BeforeEach
    void initModule() {
        module = new AudioDeviceModule(AudioLayer.kDummyAudio);
    }

    @AfterEach
    void disposeModule() {
        module.dispose();
    }

    @Test
    void setAudioSink() {
        List<AudioDevice> devices = MediaDevices.getAudioCaptureDevices();

        if (devices.isEmpty()) {
            return;
        }

        AudioSink sink = new AudioSink() {

            @Override
            public void onRecordedData(byte[] audioSamples, int nSamples,
                                       int nBytesPerSample, int nChannels, int samplesPerSec,
                                       int totalDelayMS, int clockDrift) {

            }
        };

        //module.setRecordingDevice(devices.get(0));
        module.setAudioSink(sink);
        //module.initRecording();
    }

    @Test
    void setAudioSource() {
        List<AudioDevice> devices = MediaDevices.getAudioRenderDevices();

        if (devices.isEmpty()) {
            return;
        }

        AudioSource source = new AudioSource() {

            @Override
            public int onPlaybackData(byte[] audioSamples, int nSamples,
                                      int nBytesPerSample, int nChannels, int samplesPerSec) {
                return 0;
            }
        };

        //module.setPlayoutDevice(devices.get(0));
        module.setAudioSource(source);
        //module.initPlayout();
    }
}
