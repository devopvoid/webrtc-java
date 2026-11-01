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

            if (tone.isEmpty()) {
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
            // The longest sequence in these tests needs 720 ms of tone time,
            // and timer scheduling plus the callback add about 240 ms, which
            // leaves one second almost no margin. The latch returns when the
            // sequence completes, so a five second bound costs nothing on a pass.
            return completedLatch.await(5, TimeUnit.SECONDS);
        }
    }

    TestPeerConnection caller;
    TestPeerConnection callee;

    RTCRtpSender sender;
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

        sender = peerConnection.addTrack(audioTrack, streamIds);
        dtmfSender = sender.getDtmfSender();

        caller.setRemotePeerConnection(callee);
        callee.setRemotePeerConnection(caller);

        callee.setRemoteDescription(caller.createOffer());
        caller.setRemoteDescription(callee.createAnswer());

        caller.waitUntilConnected();
        callee.waitUntilConnected();

        // The connected peer connection state does not imply the audio send
        // stream is up; DTMF insertability arrives asynchronously with it.
        // Wait for it, bounded, so the tests assert behavior rather than the
        // engine's internal activation ordering.
        for (int i = 0; i < 100 && !dtmfSender.canInsertDtmf(); i++) {
            Thread.sleep(50);
        }
    }

    @AfterEach
    void dispose() {
        caller.close();
        callee.close();

        if (sender != null) {
            sender.dispose();
        }
        dtmfSender.dispose();
    }

    @Test
    void dtmfSenderOutlivesClosedConnection() {
        TestDtmfSenderObserver observer = new TestDtmfSenderObserver();

        dtmfSender.registerObserver(observer);

        // Closing frees the connection's senders, and with the RTCRtpSender
        // gone nothing else refers to the DTMF sender. The instance must keep
        // the DTMF sender it refers to alive, rather than point to freed
        // memory; this used to crash the VM.
        caller.close();
        callee.close();
        sender.dispose();
        sender = null;

        for (int i = 0; i < 1000; i++) {
            assertFalse(dtmfSender.canInsertDtmf());
            assertFalse(dtmfSender.insertDtmf("1", 100, 70));
            assertEquals("", dtmfSender.tones());
        }

        assertDoesNotThrow(dtmfSender::unregisterObserver);
    }

    @Test
    void unregisterKeepsObserverOfOtherInstance() throws InterruptedException {
        assertObserverOfOtherInstanceStays(false);
    }

    @Test
    void disposeKeepsObserverOfOtherInstance() throws InterruptedException {
        assertObserverOfOtherInstanceStays(true);
    }

    private void assertObserverOfOtherInstanceStays(boolean dispose) throws InterruptedException {
        RTCDtmfSender first = sender.getDtmfSender();
        RTCDtmfSender second = sender.getDtmfSender();

        TestDtmfSenderObserver firstObserver = new TestDtmfSenderObserver();
        TestDtmfSenderObserver secondObserver = new TestDtmfSenderObserver();

        try {
            first.registerObserver(firstObserver);
            second.registerObserver(secondObserver);

            // The second observer replaced the first, which leaves the first
            // instance nothing to remove from the DTMF sender.
            if (dispose) {
                first.dispose();
            }
            else {
                first.unregisterObserver();
            }

            assertTrue(dtmfSender.insertDtmf("1", 100, 70));
            assertTrue(secondObserver.awaitCompletion(), "The observer of the other instance was removed");

            assertEquals(Arrays.asList("1", ""), secondObserver.getTones());
            assertTrue(firstObserver.getTones().isEmpty(), "The replaced observer must not receive events");
        }
        finally {
            if (!dispose) {
                first.dispose();
            }
            second.dispose();
        }
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

        assertEquals(Arrays.asList("1", "2", "3", ""), observer.getTones());

        dtmfSender.unregisterObserver();
        dtmfSender.insertDtmf("456", 100, 70);

        Thread.sleep(500);

        assertEquals(Arrays.asList("1", "2", "3", ""), observer.getTones()); // No new events
    }

    @Test
    void replaceObserverStopsPreviousObserver() throws InterruptedException {
        TestDtmfSenderObserver first = new TestDtmfSenderObserver();
        TestDtmfSenderObserver second = new TestDtmfSenderObserver();

        dtmfSender.registerObserver(first);

        // Replacing the observer must unregister and free the previous
        // native observer wrapper instead of leaking it and leaving it
        // registered alongside the new one.
        dtmfSender.registerObserver(second);

        assertTrue(dtmfSender.insertDtmf("1", 100, 70));
        assertTrue(second.awaitCompletion(), "Timed out waiting for DTMF sequence to complete");

        assertEquals(Arrays.asList("1", ""), second.getTones());
        assertTrue(first.getTones().isEmpty(), "The replaced observer must not receive events");

        dtmfSender.unregisterObserver();
    }

    private void insertTones(List<String> tones, int duration, int interToneGap) throws InterruptedException {
        TestDtmfSenderObserver observer = new TestDtmfSenderObserver();

        dtmfSender.registerObserver(observer);
        assertTrue(dtmfSender.insertDtmf(String.join("", tones), duration, interToneGap));

        // Wait until the DTMF sequence completes (observer receives an empty tone).
        assertTrue(observer.awaitCompletion(),
                "Timed out waiting for DTMF sequence to complete");

        // All tones should have been delivered to the observer in order, followed by an empty one.
        List<String> expectedTones = new ArrayList<>(tones);
        expectedTones.add("");
        assertEquals(expectedTones, observer.getTones());

        // After completion, the internal tone buffer should be empty.
        assertEquals("", dtmfSender.tones());

        dtmfSender.unregisterObserver();
    }
}
