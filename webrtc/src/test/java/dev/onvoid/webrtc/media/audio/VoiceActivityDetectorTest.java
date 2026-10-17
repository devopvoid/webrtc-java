/*
 * Copyright 2021 Alex Andres
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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import dev.onvoid.webrtc.TestBase;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VoiceActivityDetectorTest extends TestBase {

    private VoiceActivityDetector detector;

    @BeforeEach
    void setUp() {
        detector = new VoiceActivityDetector();
    }

    @AfterEach
    void tearDown() {
        detector.dispose();
    }

    @Test
    void subclassFieldsDoNotHideHandle() {
        // NativeObject's private pointer must not bind to a subclass's unrelated field.
        // Nonzero values used to be interpreted as pointers by process()/dispose().
        for (long value : new long[] {0, 7, Long.MIN_VALUE, Long.MAX_VALUE}) {
            ShadowedDetector vad = new ShadowedDetector(value);
            try {
                vad.process(new byte[320], 160, 16000);
                assertEquals(1.0f, vad.getLastVoiceProbability());
                assertEquals(value, vad.nativeHandle);
            }
            finally {
                vad.dispose();
            }
        }
    }

    private static class ShadowedDetector extends VoiceActivityDetector {
        private long nativeHandle;

        private ShadowedDetector(long value) {
            nativeHandle = value;
        }
    }

    @Test
    void preservesProbabilityDelay() {
        // Upstream starts at 1 and updates after collecting three 10 ms chunks.
        // This also guards against replacing this estimator with binary decisions.
        assertEquals(1.0f, detector.getLastVoiceProbability());
        detector.process(new byte[320], 160, 16000);
        detector.process(new byte[320], 160, 16000);
        assertEquals(1.0f, detector.getLastVoiceProbability());
        detector.process(new byte[320], 160, 16000);
        assertEquals(0.01f, detector.getLastVoiceProbability(), 0.000001f);
    }

    @Test
    void acceptsSupportedRates() {
        for (int rate : new int[] {8000, 16000, 32000, 48000}) {
            VoiceActivityDetector vad = new VoiceActivityDetector();
            try {
                for (int i = 0; i < 6; i++) {
                    vad.process(new byte[rate / 100 * 2], rate / 100, rate);
                }
                assertEquals(0.01f, vad.getLastVoiceProbability(), 0.000001f);
            }
            finally {
                vad.dispose();
            }
        }
    }

    @Test
    void detectsSpeechProbability() throws Exception {
        byte[] speech = speech();
        float maximum = 0;
        for (int offset = 0; offset < speech.length; offset += 320) {
            detector.process(Arrays.copyOfRange(speech, offset, offset + 320), 160, 16000);
            float probability = detector.getLastVoiceProbability();
            assertTrue(probability >= 0 && probability <= 1);
            if (offset >= 640) {
                assertTrue(probability > 0 && probability < 1, "Expected a non-binary probability");
            }
            // Ignore the initial value of 1 before the first probability update.
            if (offset >= 640) {
                maximum = Math.max(maximum, probability);
            }
        }
        assertTrue(maximum > 0.5f, "Expected speech in the synthetic speech fixture");
        for (int i = 0; i < 100; i++) {
            detector.process(new byte[320], 160, 16000);
        }
        assertEquals(0.01f, detector.getLastVoiceProbability(), 0.000001f);
    }

    @Test
    void rejectsInvalidInput() {
        assertThrows(NullPointerException.class, () -> detector.process(null, 160, 16000));
        for (int count : new int[] {-1, 0, 159, 161, 320, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> detector.process(new byte[320], count, 16000));
        }
        for (int rate : new int[] {-16000, 0, 44100, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> detector.process(new byte[320], 160, rate));
        }
        assertThrows(IllegalArgumentException.class, () -> detector.process(new byte[319], 160, 16000));
        // Existing callers may supply a larger buffer; only the requested samples are read.
        detector.process(new byte[640], 160, 16000);
    }

    @Test
    void historiesAreIndependent() throws Exception {
        StandaloneVoiceActivityDetector standalone = new StandaloneVoiceActivityDetector();
        VoiceActivityDetector other = new VoiceActivityDetector();
        try {
            byte[] speech = speech();
            for (int offset = 0; offset < speech.length; offset += 320) {
                short[] frame = new short[160];
                ByteBuffer.wrap(speech, offset, 320).order(ByteOrder.nativeOrder()).asShortBuffer().get(frame);
                standalone.isSpeech(frame, 16000);
                other.process(Arrays.copyOfRange(speech, offset, offset + 320), 160, 16000);
            }
            assertEquals(1.0f, detector.getLastVoiceProbability());
            for (int i = 0; i < 3; i++) {
                detector.process(new byte[320], 160, 16000);
            }
            assertEquals(0.01f, detector.getLastVoiceProbability(), 0.000001f);
            standalone.reset();
            standalone.setMode(StandaloneVoiceActivityDetector.Mode.VERY_AGGRESSIVE);
            other.dispose();
            assertEquals(0.01f, detector.getLastVoiceProbability(), 0.000001f);
        }
        finally {
            standalone.dispose();
            other.dispose();
        }
    }

    @Test
    void repeatedLifecycles() {
        byte[] frame = new byte[320];
        for (int i = 0; i < 1000; i++) {
            VoiceActivityDetector vad = new VoiceActivityDetector();
            try {
                for (int j = 0; j < 3; j++) {
                    vad.process(frame, 160, 16000);
                }
                assertEquals(0.01f, vad.getLastVoiceProbability(), 0.000001f);
            }
            finally {
                vad.dispose();
                vad.dispose();
            }
        }
    }

    @Test
    void fullScaleAudioDoesNotCrash() {
        byte[] frame = new byte[960];
        ByteBuffer pcm = ByteBuffer.wrap(frame).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 480; i++) {
            pcm.putShort(i % 2 == 0 ? Short.MIN_VALUE : Short.MAX_VALUE);
        }
        for (int i = 0; i < 1000; i++) {
            detector.process(frame, 480, 48000);
            float probability = detector.getLastVoiceProbability();
            assertTrue(probability >= 0 && probability <= 1);
        }
    }

    @Test
    void disposalIsIdempotent() {
        detector.dispose();
        detector.dispose();
        assertThrows(IllegalStateException.class, () -> detector.process(new byte[320], 160, 16000));
        assertThrows(IllegalStateException.class, () -> detector.getLastVoiceProbability());
    }

    @Test
    void disposalCanRaceProcessing() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> processing = executor.submit(() -> {
                start.await();
                for (int i = 0; i < 1000; i++) {
                    try {
                        detector.process(new byte[320], 160, 16000);
                        detector.getLastVoiceProbability();
                    }
                    catch (IllegalStateException disposed) {
                        break;
                    }
                }
                return null;
            });
            Future<?> disposing = executor.submit(() -> {
                start.await();
                detector.dispose();
                return null;
            });
            start.countDown();
            processing.get(10, TimeUnit.SECONDS);
            disposing.get(10, TimeUnit.SECONDS);
        }
        finally {
            executor.shutdownNow();
        }
    }

    private static byte[] speech() throws Exception {
        try (InputStream input = VoiceActivityDetectorTest.class.getResourceAsStream("/audio/vad-speech-16k.pcm")) {
            assertNotNull(input);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int count;
            while ((count = input.read(chunk)) != -1) {
                bytes.write(chunk, 0, count);
            }
            byte[] pcm = bytes.toByteArray();
            ByteBuffer source = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer target = ByteBuffer.allocate(pcm.length).order(ByteOrder.nativeOrder());
            while (source.hasRemaining()) {
                target.putShort(source.getShort());
            }
            return target.array();
        }
    }
}
