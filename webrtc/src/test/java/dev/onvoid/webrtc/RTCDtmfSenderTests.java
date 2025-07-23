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
    void getTones() {
        dtmfSender.insertDtmf("123", 100, 70);
        assertEquals("123", dtmfSender.tones());
    }

    @Test
    void getDuration() {
        assertEquals(100, dtmfSender.duration()); // Default value

        assertTrue(dtmfSender.insertDtmf("123", 120, 70));
        assertEquals(120, dtmfSender.duration());
        assertTrue(dtmfSender.insertDtmf("456", 170, 70));
        assertEquals(170, dtmfSender.duration());
    }

    @Test
    void getInterToneGap() {
        assertEquals(50, dtmfSender.interToneGap()); // Default value

        assertTrue(dtmfSender.insertDtmf("123", 100, 70));
        assertEquals(70, dtmfSender.interToneGap());
        assertTrue(dtmfSender.insertDtmf("456", 100, 60));
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
}