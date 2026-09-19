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
    void pushAudioRejectsBadArguments() {
        byte[] data = new byte[480 * 2 * 2];

        assertThrows(NullPointerException.class,
                () -> customAudioSource.pushAudio(null, 16, 48000, 2, 480));

        // WebRTC reads the samples as 16-bit PCM whatever is declared here, so
        // any other width would be read as the wrong number of bytes.
        assertThrows(IllegalArgumentException.class,
                () -> customAudioSource.pushAudio(data, 8, 48000, 2, 480));
        assertThrows(IllegalArgumentException.class,
                () -> customAudioSource.pushAudio(data, 32, 48000, 2, 480));

        assertThrows(IllegalArgumentException.class,
                () -> customAudioSource.pushAudio(data, 16, 0, 2, 480));
        assertThrows(IllegalArgumentException.class,
                () -> customAudioSource.pushAudio(data, 16, 48000, 0, 480));
        assertThrows(IllegalArgumentException.class,
                () -> customAudioSource.pushAudio(data, 16, 48000, 2, 0));
        assertThrows(IllegalArgumentException.class,
                () -> customAudioSource.pushAudio(data, 16, 48000, -1, 480));
    }

    @Test
    void pushAudioRejectsArrayShorterThanTheFramesItClaims() {
        // Native code reads frameCount * channels * 2 bytes out of the array.
        // Without this check a short array is read past its end.
        byte[] data = new byte[480 * 2 * 2];

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> customAudioSource.pushAudio(data, 16, 48000, 2, 481));

        assertTrue(e.getMessage().contains("1924"), e.getMessage());

        // Exactly the required size is fine, and so is a longer array.
        customAudioSource.pushAudio(data, 16, 48000, 2, 480);
        customAudioSource.pushAudio(new byte[8192], 16, 48000, 2, 480);
    }

    @Test
    void pushAudioRejectsChunkLargerThanWebRtcTakes() {
        // WebRTC copies a chunk into a fixed-size frame and aborts the process
        // when it does not fit, so an oversized chunk must not reach it.
        int frames = CustomAudioSource.MAX_SAMPLES_PER_PUSH / 2 + 1;
        byte[] data = new byte[frames * 2 * 2];

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> customAudioSource.pushAudio(data, 16, 48000, 2, frames));

        assertTrue(e.getMessage().contains(String.valueOf(
                CustomAudioSource.MAX_SAMPLES_PER_PUSH)), e.getMessage());

        // The largest chunk that still fits is accepted.
        int maxFrames = CustomAudioSource.MAX_SAMPLES_PER_PUSH / 2;

        customAudioSource.pushAudio(new byte[maxFrames * 2 * 2], 16, 48000, 2, maxFrames);
    }

    @Test
    void pushAudioWithTimestamp() {
        // The timestamped overload takes the same chunks and rejects the same
        // bad arguments; only the capture time it reports differs.
        byte[] data = new byte[480 * 2 * 2];
        long timestampUs = SyncClock.currentTimeUs();

        customAudioSource.pushAudio(data, 16, 48000, 2, 480, timestampUs);
        customAudioSource.pushAudio(data, 16, 48000, 2, 480, timestampUs + 10_000);

        assertThrows(NullPointerException.class,
                () -> customAudioSource.pushAudio(null, 16, 48000, 2, 480, timestampUs));
        assertThrows(IllegalArgumentException.class,
                () -> customAudioSource.pushAudio(data, 8, 48000, 2, 480, timestampUs));
        assertThrows(IllegalArgumentException.class,
                () -> customAudioSource.pushAudio(data, 16, 48000, 2, 481, timestampUs));
        assertThrows(IllegalArgumentException.class,
                () -> customAudioSource.pushAudio(data, 16, 48000, 0, 480, timestampUs));
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
    void concurrentAddRemoveSinkDoesNotCrash() throws InterruptedException {
        // Regression test: AddSink()/RemoveSink() (called here from this thread,
        // mirroring the internal thread WebRTC uses as tracks attach/detach) used
        // to race unsynchronized with PushAudioData() (the application's capture
        // thread) over the native sinks_ vector.
        AudioTrack audioTrack = factory.createAudioTrack("audioTrack", customAudioSource);
        AudioTrackSink sink = (data, bitsPerSample, sampleRate, channels, frames) -> { };

        byte[] audioData = new byte[480 * 2 * 2]; // 10ms of 48kHz stereo 16-bit audio

        AtomicBoolean running = new AtomicBoolean(true);

        Thread pushThread = new Thread(() -> {
            while (running.get()) {
                customAudioSource.pushAudio(audioData, 16, 48000, 2, 480);
            }
        });
        pushThread.start();

        for (int i = 0; i < 5000; i++) {
            audioTrack.addSink(sink);
            audioTrack.removeSink(sink);
        }

        running.set(false);
        pushThread.join(5000);

        audioTrack.dispose();
    }

    @Test
    void pushAudioWithDifferentFormats() {
        // Every rate and channel count is passed through unchanged. Only 16-bit
        // samples are accepted, since that is what WebRTC reads.
        testAudioFormat(16, 8000, 1, 80);    // 8kHz, mono, 10ms
        testAudioFormat(16, 16000, 1, 160);  // 16kHz, mono, 10ms
        testAudioFormat(16, 44100, 2, 441);  // 44.1kHz, stereo, 10ms
        testAudioFormat(16, 48000, 2, 480);  // 48kHz, stereo, 10ms
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