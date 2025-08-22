/*
 * Copyright 2019 Alex Andres
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

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link RTCDtmfSender} class.
 */
class RTCDtmfSenderTests extends TestBase {

    /**
     * Test implementation of RTCDtmfSenderObserver that records tone change events.
     */
    private static class TestDtmfSenderObserver implements RTCDtmfSenderObserver {

        private final List<String> tones = new ArrayList<>();
        private final List<String> toneBuffers = new ArrayList<>();
        private final CountDownLatch completedLatch = new CountDownLatch(1);


        @Override
        public void onToneChange(String tone, String toneBuffer) {
            tones.add(tone);
            toneBuffers.add(toneBuffer);

            if (tone == null || tone.isEmpty()) {
                completedLatch.countDown();
            }
        }

        List<String> getTones() {
            return tones;
        }

        List<String> getToneBuffers() {
            return toneBuffers;
        }

        void waitUntilCompleted() throws InterruptedException {
            completedLatch.await();
        }

        boolean awaitCompletion() throws InterruptedException {
            return completedLatch.await(1, TimeUnit.SECONDS);
        }
    }

    TestPeerConnection caller;
    TestPeerConnection callee;

    RTCDtmfSender dtmfSender;


    @BeforeEach
    void init() throws Exception {
        caller = new TestPeerConnection(factory);
        callee = new TestPeerConnection(factory);

        RTCPeerConnection peerConnection = caller.getPeerConnection();

        AudioOptions audioOptions = new AudioOptions();
        AudioTrackSource audioSource = factory.createAudioSource(audioOptions);
        AudioTrack audioTrack = factory.createAudioTrack("audioTrack", audioSource);

        List<String> streamIds = new ArrayList<>();
        streamIds.add("stream1");

        RTCRtpSender sender = peerConnection.addTrack(audioTrack, streamIds);
        dtmfSender = sender.getDtmfSender();

        caller.setRemotePeerConnection(callee);
        callee.setRemotePeerConnection(caller);

        callee.setRemoteDescription(caller.createOffer());
        caller.setRemoteDescription(callee.createAnswer());

        caller.waitUntilConnected();
        callee.waitUntilConnected();
    }

    @AfterEach
    void dispose() {
        caller.close();
        callee.close();
    }

    @Test
    void canInsertDtmf() {
        assertNotNull(dtmfSender);
        assertTrue(dtmfSender.canInsertDtmf(), "DTMF sender should be able to insert DTMF tones.");
    }

    @Test
    void insertDtmf() {
        assertTrue(dtmfSender.insertDtmf("123", 100, 70));
        assertTrue(dtmfSender.insertDtmf("ABC", 100, 70));
        assertTrue(dtmfSender.insertDtmf("#*,", 100, 70));
        assertFalse(dtmfSender.insertDtmf("123", 30, 70)); // Duration too short
        assertFalse(dtmfSender.insertDtmf("123", 100, 20)); // InterToneGap too short
    }

    @Test
    void getTones() throws InterruptedException {
        insertTones(Arrays.asList("1", "2", "3"), 100, 70);
    }

    @Test
    void getDuration() throws InterruptedException {
        assertEquals(100, dtmfSender.duration()); // Default value

        insertTones(Arrays.asList("1", "2", "3"), 120, 70);
        assertEquals(120, dtmfSender.duration());

        insertTones(Arrays.asList("4", "5", "6"), 170, 70);
        assertEquals(170, dtmfSender.duration());
    }

    @Test
    void getInterToneGap() throws InterruptedException {
        assertEquals(50, dtmfSender.interToneGap()); // Default value

        insertTones(Arrays.asList("1", "2", "3"), 100, 70);
        assertEquals(70, dtmfSender.interToneGap());

        insertTones(Arrays.asList("4", "5", "6"), 100, 60);
        assertEquals(60, dtmfSender.interToneGap());
    }

    @Test
    void registerAndUnregisterObserver() throws InterruptedException {
        TestDtmfSenderObserver observer = new TestDtmfSenderObserver();

        dtmfSender.registerObserver(observer);
        dtmfSender.insertDtmf("123", 100, 70);

        observer.waitUntilCompleted();

        assertEquals(Arrays.asList("1", "2", "3", null), observer.getTones());

        dtmfSender.unregisterObserver();
        dtmfSender.insertDtmf("456", 100, 70);

        Thread.sleep(500);

        assertEquals(Arrays.asList("1", "2", "3", null), observer.getTones()); // No new events
    }

    private void insertTones(List<String> tones, int duration, int interToneGap) throws InterruptedException {
        TestDtmfSenderObserver observer = new TestDtmfSenderObserver();

        dtmfSender.registerObserver(observer);
        assertTrue(dtmfSender.insertDtmf(String.join("", tones), duration, interToneGap));

        // Wait until the DTMF sequence completes (observer receives null/empty tone).
        assertTrue(observer.awaitCompletion(),
                "Timed out waiting for DTMF sequence to complete");

        // All tones should have been delivered to the observer in order, followed by null.
        List<String> expectedTones = new ArrayList<>(tones);
        expectedTones.add(null);
        assertEquals(expectedTones, observer.getTones());

        // After completion, the internal tone buffer should be empty.
        assertNull(dtmfSender.tones());

        dtmfSender.unregisterObserver();
    }
}
