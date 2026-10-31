/*
 * Copyright 2026 Alex Andres
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.onvoid.webrtc.media.audio;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCPeerConnection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Requires a running PulseAudio-compatible server. Run explicitly with
 * -Dwebrtc.test.pulseaudio=true; PULSE_SINK and PULSE_SOURCE can select a
 * virtual sink and its monitor so no speakers or microphone are used.
 */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "webrtc.test.pulseaudio", matches = "true")
@Execution(ExecutionMode.SAME_THREAD)
class PulseAudioIntegrationTest {

    @Test
    void playoutRestarts() throws Exception {
        restartAudio(false);
    }

    @Test
    void recordingRestarts() throws Exception {
        restartAudio(true);
    }

    private void restartAudio(boolean recording) throws Exception {
        AudioDeviceModule adm = new AudioDeviceModule(AudioLayer.kLinuxPulseAudio);
        try {
            PeerConnectionFactory factory = new PeerConnectionFactory(adm);
            try {
                // Closing the last peer terminates the ADM. Each subsequent
                // peer must reinitialize both PulseAudio processing threads.
                for (int cycle = 1; cycle <= 5; cycle++) {
                    RTCPeerConnection peer = factory.createPeerConnection(
                            new RTCConfiguration(), new PeerConnectionObserver() {
                                @Override
                                public void onIceCandidate(RTCIceCandidate candidate) { }
                            });
                    try {
                        System.out.printf("PulseAudio %s cycle %d%n",
                                recording ? "recording" : "playout", cycle);
                        CountDownLatch frames = new CountDownLatch(3);
                        if (recording) {
                            adm.setAudioSink((data, samples, bytes, channels, rate, delay, drift) -> {
                                if (samples > 0) {
                                    frames.countDown();
                                }
                            });
                            adm.initRecording();
                            adm.startRecording();
                        }
                        else {
                            adm.setAudioSource((data, samples, bytes, channels, rate) -> {
                                frames.countDown();
                                return samples;
                            });
                            adm.initPlayout();
                            adm.startPlayout();
                        }
                        assertTrue(frames.await(5, TimeUnit.SECONDS),
                                "No audio callbacks in cycle " + cycle);
                    }
                    finally {
                        peer.close();
                    }
                }
            }
            finally {
                factory.dispose();
            }
        }
        finally {
            adm.dispose();
        }
    }
}
