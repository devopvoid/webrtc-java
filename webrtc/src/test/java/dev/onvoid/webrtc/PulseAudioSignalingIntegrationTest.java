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

package dev.onvoid.webrtc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import dev.onvoid.webrtc.media.MediaType;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;
import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/** See PulseAudioIntegrationTest for the optional PulseAudio server setup. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "webrtc.test.pulseaudio", matches = "true")
@Execution(ExecutionMode.SAME_THREAD)
class PulseAudioSignalingIntegrationTest extends TestBase {

    @Test
    void remoteAnswerRestartsPlayout() throws Exception {
        AudioDeviceModule adm = new AudioDeviceModule(AudioLayer.kLinuxPulseAudio);
        try {
            PeerConnectionFactory receiverFactory = new PeerConnectionFactory(adm);
            try {
                AudioTrackSource source = factory.createAudioSource(new AudioOptions());
                try {
                    AudioTrack track = factory.createAudioTrack("audio", source);
                    try {
                        for (int cycle = 1; cycle <= 5; cycle++) {
                            negotiate(receiverFactory, track, cycle);
                        }
                    }
                    finally {
                        track.dispose();
                    }
                }
                finally {
                    source.dispose();
                }
            }
            finally {
                receiverFactory.dispose();
            }
        }
        finally {
            adm.dispose();
        }
    }

    private void negotiate(PeerConnectionFactory receiverFactory, AudioTrack track,
            int cycle) throws Exception {
        PeerConnectionObserver observer = new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) { }
        };
        RTCPeerConnection receiver = receiverFactory.createPeerConnection(
                new RTCConfiguration(), observer);
        try {
            RTCPeerConnection sender = factory.createPeerConnection(new RTCConfiguration(), observer);
            try {
                RTCRtpTransceiverInit init = new RTCRtpTransceiverInit();
                init.direction = RTCRtpTransceiverDirection.RECV_ONLY;
                receiver.addTransceiver(MediaType.AUDIO, init).dispose();
                sender.addTrack(track, Collections.singletonList("stream")).dispose();

                TestCreateDescObserver offerObserver = new TestCreateDescObserver();
                receiver.createOffer(new RTCOfferOptions(), offerObserver);
                RTCSessionDescription offer = offerObserver.get(15, TimeUnit.SECONDS);
                TestSetDescObserver localOffer = new TestSetDescObserver();
                receiver.setLocalDescription(offer, localOffer);
                localOffer.get(15, TimeUnit.SECONDS);
                TestSetDescObserver remoteOffer = new TestSetDescObserver();
                sender.setRemoteDescription(offer, remoteOffer);
                remoteOffer.get(15, TimeUnit.SECONDS);

                TestCreateDescObserver answerObserver = new TestCreateDescObserver();
                sender.createAnswer(new RTCAnswerOptions(), answerObserver);
                RTCSessionDescription answer = answerObserver.get(15, TimeUnit.SECONDS);
                TestSetDescObserver localAnswer = new TestSetDescObserver();
                sender.setLocalDescription(answer, localAnswer);
                localAnswer.get(15, TimeUnit.SECONDS);

                // Applying the answer starts PulseAudio even without ICE or RTP.
                // A dead processing thread causes a synchronous ten-second wait.
                TestSetDescObserver remoteAnswer = new TestSetDescObserver();
                long started = System.nanoTime();
                receiver.setRemoteDescription(answer, remoteAnswer);
                remoteAnswer.get(15, TimeUnit.SECONDS);
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                System.out.printf("PulseAudio remote answer cycle %d: %d ms%n", cycle, elapsed);
                assertTrue(elapsed < 5000,
                        "Audio startup timed out in cycle " + cycle + ": " + elapsed + " ms");
            }
            finally {
                sender.close();
            }
        }
        finally {
            receiver.close();
        }
    }
}
