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

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import dev.onvoid.webrtc.TestBase;
import dev.onvoid.webrtc.media.audio.StandaloneVoiceActivityDetector.Mode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StandaloneVoiceActivityDetectorTest extends TestBase {

    private StandaloneVoiceActivityDetector detector;

    @BeforeEach
    void setUp() {
        detector = new StandaloneVoiceActivityDetector();
    }

    @AfterEach
    void tearDown() {
        detector.dispose();
    }

    @Test
    void acceptsSupportedFrames() {
        for (Mode mode : Mode.values()) {
            detector.setMode(mode);
            for (int rate : new int[] {8000, 16000, 32000, 48000}) {
                for (int ms : new int[] {10, 20, 30}) {
                    detector.reset();
                    assertFalse(detector.isSpeech(new short[rate / 1000 * ms], rate),
                            mode + " " + rate + " Hz / " + ms + " ms silence");
                }
            }
        }
    }

    @Test
    void detectsSpeech() throws Exception {
        short[] speech = speech();
        for (Mode mode : Mode.values()) {
            detector.setMode(mode);
            detector.reset();
            int active = 0;
            for (int offset = 0; offset < speech.length; offset += 160) {
                if (detector.isSpeech(Arrays.copyOfRange(speech, offset, offset + 160), 16000)) {
                    active++;
                }
            }
            assertTrue(active > 50, "Expected most synthetic speech frames to be active in " + mode);
            // Hangover can keep a few silent frames active; sustained silence must clear it.
            boolean last = true;
            for (int i = 0; i < 100; i++) {
                last = detector.isSpeech(new short[160], 16000);
            }
            assertFalse(last);
        }
    }

    @Test
    void rejectsInvalidInput() {
        assertThrows(NullPointerException.class, () -> detector.isSpeech(null, 16000));
        assertThrows(NullPointerException.class, () -> new StandaloneVoiceActivityDetector(null));
        assertThrows(NullPointerException.class, () -> detector.setMode(null));
        for (int rate : new int[] {-16000, 0, 44100, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> detector.isSpeech(new short[160], rate));
        }
        for (int size : new int[] {0, 1, 159, 161, 640, 1441}) {
            assertThrows(IllegalArgumentException.class, () -> detector.isSpeech(new short[size], 16000));
        }
    }

    @Test
    void nativeBoundaryRejectsInvalidFrames() throws Exception {
        // Bypass Java validation to exercise the bounds check protecting the JNI copy.
        Method process = StandaloneVoiceActivityDetector.class.getDeclaredMethod("processInternal", short[].class, int.class);
        process.setAccessible(true);
        for (short[] frame : new short[][] {new short[0], new short[159], new short[1441]}) {
            InvocationTargetException error = assertThrows(InvocationTargetException.class,
                    () -> process.invoke(detector, frame, 16000));
            assertTrue(error.getCause() instanceof IllegalArgumentException);
        }
        InvocationTargetException rateError = assertThrows(InvocationTargetException.class,
                () -> process.invoke(detector, new short[160], 44100));
        assertTrue(rateError.getCause() instanceof IllegalArgumentException);
        InvocationTargetException nullError = assertThrows(InvocationTargetException.class,
                () -> process.invoke(detector, null, 16000));
        assertTrue(nullError.getCause() instanceof NullPointerException);
        assertFalse(detector.isSpeech(new short[160], 16000));
    }

    @Test
    void resetPreservesMode() throws Exception {
        short[] speech = speech();
        for (Mode mode : Mode.values()) {
            detector.setMode(mode);
            detector.reset();
            boolean[] expected = decisions(detector, speech);
            detector.reset();
            assertArrayEquals(expected, decisions(detector, speech));
            StandaloneVoiceActivityDetector fresh = new StandaloneVoiceActivityDetector(mode);
            try {
                assertArrayEquals(decisions(fresh, speech), expected);
            }
            finally {
                fresh.dispose();
            }
        }
    }

    @Test
    void instancesKeepSeparateHistory() throws Exception {
        StandaloneVoiceActivityDetector other = new StandaloneVoiceActivityDetector();
        try {
            decisions(detector, speech());
            assertFalse(other.isSpeech(new short[160], 16000));
            detector.dispose();
            assertFalse(other.isSpeech(new short[160], 16000));
        }
        finally {
            other.dispose();
        }
    }

    @Test
    void repeatedLifecycles() {
        short[] frame = new short[1440];
        for (int i = 0; i < 1000; i++) {
            StandaloneVoiceActivityDetector vad = new StandaloneVoiceActivityDetector(Mode.values()[i % 4]);
            try {
                assertFalse(vad.isSpeech(frame, 48000));
                vad.reset();
                assertFalse(vad.isSpeech(frame, 48000));
            }
            finally {
                vad.dispose();
                vad.dispose();
            }
        }
    }

    @Test
    void fullScaleAudioDoesNotCrash() {
        short[] frame = new short[1440];
        for (int i = 0; i < frame.length; i++) {
            frame[i] = i % 2 == 0 ? Short.MIN_VALUE : Short.MAX_VALUE;
        }
        for (int i = 0; i < 1000; i++) {
            detector.setMode(Mode.values()[i % 4]);
            detector.isSpeech(frame, 48000);
        }
    }

    @Test
    void disposalIsIdempotent() {
        detector.dispose();
        detector.dispose();
        assertThrows(IllegalStateException.class, () -> detector.isSpeech(new short[160], 16000));
        assertThrows(IllegalStateException.class, () -> detector.setMode(Mode.AGGRESSIVE));
        assertThrows(IllegalStateException.class, () -> detector.reset());
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
                        detector.isSpeech(new short[160], 16000);
                        detector.setMode(Mode.AGGRESSIVE);
                        detector.reset();
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
            assertThrows(IllegalStateException.class, () -> detector.reset());
        }
        finally {
            executor.shutdownNow();
        }
    }

    private static boolean[] decisions(StandaloneVoiceActivityDetector vad, short[] samples) {
        boolean[] result = new boolean[samples.length / 160];
        for (int i = 0; i < result.length; i++) {
            result[i] = vad.isSpeech(Arrays.copyOfRange(samples, i * 160, (i + 1) * 160), 16000);
        }
        return result;
    }

    private static short[] speech() throws Exception {
        try (InputStream input = StandaloneVoiceActivityDetectorTest.class.getResourceAsStream("/audio/vad-speech-16k.pcm")) {
            assertNotNull(input);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int count;
            while ((count = input.read(chunk)) != -1) {
                bytes.write(chunk, 0, count);
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
            short[] samples = new short[buffer.remaining() / 2];
            buffer.asShortBuffer().get(samples);
            return samples;
        }
    }
}
