/*
 * Copyright 2025 Alex Andres
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

import static org.junit.jupiter.api.Assertions.*;

import dev.onvoid.webrtc.TestBase;
import dev.onvoid.webrtc.media.MediaSource;
import dev.onvoid.webrtc.media.SyncClock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class CustomAudioSourceTest extends TestBase {

    private CustomAudioSource customAudioSource;


    @BeforeEach
    void init() {
        customAudioSource = new CustomAudioSource();
    }

    @AfterEach
    void dispose() {

    }

    @Test
    void stateAfterCreation() {
        assertEquals(MediaSource.State.LIVE, customAudioSource.getState());
    }

    @Test
    void addNullSink() {
        assertThrows(NullPointerException.class, () -> {
            AudioTrack audioTrack = factory.createAudioTrack("audioTrack", customAudioSource);
            audioTrack.addSink(null);
            audioTrack.dispose();
        });
    }

    @Test
    void removeNullSink() {
        assertThrows(NullPointerException.class, () -> {
            AudioTrack audioTrack = factory.createAudioTrack("audioTrack", customAudioSource);
            audioTrack.removeSink(null);
            audioTrack.dispose();
        });
    }

    @Test
    void addRemoveSink() {
        AudioTrack audioTrack = factory.createAudioTrack("audioTrack", customAudioSource);
        AudioTrackSink sink = (data, bitsPerSample, sampleRate, channels, frames) -> { };

        audioTrack.addSink(sink);
        audioTrack.removeSink(sink);
        audioTrack.dispose();
    }

    @Test
    void pushAudioData() {
        // 16-bit, 48kHz, stereo, 10ms
        testAudioFormat(16, 48000, 2, 480);
    }

    @Test
    void pushAudioWithDifferentFormats() {
        testAudioFormat(8, 8000, 1, 80);    // 8-bit, 8kHz, mono, 10ms
        testAudioFormat(16, 16000, 1, 160); // 16-bit, 16kHz, mono, 10ms
        testAudioFormat(16, 44100, 2, 441); // 16-bit, 44.1kHz, stereo, 10ms
        testAudioFormat(16, 48000, 2, 480); // 16-bit, 48kHz, stereo, 10ms
    }
    
    @Test
    void constructWithSyncClock() {
        // Create a SyncClock.
        SyncClock clock = new SyncClock();
        
        // Create a CustomAudioSource with the clock.
        CustomAudioSource sourceWithClock = new CustomAudioSource(clock);
        
        // Verify the source is created correctly.
        assertEquals(MediaSource.State.LIVE, sourceWithClock.getState());
        
        // Test basic functionality.
        AudioTrack audioTrack = factory.createAudioTrack("audioTrack", sourceWithClock);
        
        final AtomicBoolean dataReceived = new AtomicBoolean(false);
        AudioTrackSink testSink = (data, bits, rate, chans, frames) -> {
            dataReceived.set(true);
        };
        
        audioTrack.addSink(testSink);
        
        // Create a buffer with test audio data.
        byte[] audioData = new byte[480 * 2 * 2]; // 10ms of 48kHz stereo 16-bit audio
        
        // Push audio data.
        sourceWithClock.pushAudio(audioData, 16, 48000, 2, 480);
        
        // Verify that our sink received the data.
        assertTrue(dataReceived.get(), "Audio data was not received by the sink");
        
        // Clean up
        audioTrack.removeSink(testSink);
        audioTrack.dispose();
        sourceWithClock.dispose();
        clock.dispose();
    }

    private void testAudioFormat(int bitsPerSample, int sampleRate, int channels, int frameCount) {
        AudioTrack audioTrack = factory.createAudioTrack("audioTrack", customAudioSource);

        final AtomicBoolean dataReceived = new AtomicBoolean(false);
        final AtomicInteger receivedBitsPerSample = new AtomicInteger(0);
        final AtomicInteger receivedSampleRate = new AtomicInteger(0);
        final AtomicInteger receivedChannels = new AtomicInteger(0);
        final AtomicInteger receivedFrames = new AtomicInteger(0);

        AudioTrackSink testSink = (data, bits, rate, chans, frames) -> {
            dataReceived.set(true);
            receivedBitsPerSample.set(bits);
            receivedSampleRate.set(rate);
            receivedChannels.set(chans);
            receivedFrames.set(frames);
        };

        audioTrack.addSink(testSink);

        // Create a buffer with test audio data (silence in this case).
        int bytesPerSample = bitsPerSample / 8;
        byte[] audioData = new byte[frameCount * channels * bytesPerSample];

        customAudioSource.pushAudio(audioData, bitsPerSample, sampleRate, channels, frameCount);

        // Verify that our sink received the data with correct parameters.
        assertTrue(dataReceived.get(), "Audio data was not received by the sink");
        assertEquals(bitsPerSample, receivedBitsPerSample.get(), "Bits per sample doesn't match");
        assertEquals(sampleRate, receivedSampleRate.get(), "Sample rate doesn't match");
        assertEquals(channels, receivedChannels.get(), "Channel count doesn't match");
        assertEquals(frameCount, receivedFrames.get(), "Frame count doesn't match");

        // Clean up.
        audioTrack.removeSink(testSink);
        audioTrack.dispose();
    }
}