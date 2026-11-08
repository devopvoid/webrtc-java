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

import static org.junit.jupiter.api.Assertions.*;

import dev.onvoid.webrtc.TestBase;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lifecycle tests of {@link SystemAudioSource}, with backends that stand in for
 * the platform capture, so that no audio hardware is needed.
 */
class SystemAudioSourceTest extends TestBase {

    /** One 10 ms chunk of 48 kHz stereo silence. */
    private static final byte[] CHUNK = new byte[480 * 2 * 2];


    @Test
    void restartAndDispose() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        AtomicReference<Backend> latest = new AtomicReference<>();
        SystemAudioSource source = new SystemAudioSource(() -> {
            opened.incrementAndGet();

            Backend backend = new Backend();
            latest.set(backend);

            return backend;
        });

        try {
            assertFalse(source.isRecording());

            source.stop();
            source.start();

            assertTrue(latest.get().pumped.await(5, TimeUnit.SECONDS));

            // Starting a running source does not open the output again.
            source.start();
            assertEquals(1, opened.get());

            source.stop();
            assertEquals(0, latest.get().closed.getCount());
            assertFalse(source.isRecording());

            source.start();
            assertEquals(2, opened.get());
            assertNull(source.getFailure());
        }
        finally {
            source.dispose();
        }

        source.dispose();

        assertThrows(IllegalStateException.class, source::start);
    }

    @Test
    void startupFailureClosesBackend() {
        IllegalStateException denied = new IllegalStateException("permission denied");
        Backend backend = new Backend() {
            @Override
            public void initialize() {
                super.initialize();
                throw denied;
            }
        };

        try (SystemAudioSource source = new SystemAudioSource(() -> backend)) {
            assertSame(denied, assertThrows(IllegalStateException.class, source::start).getCause());
            assertSame(denied, source.getFailure());
            assertEquals(0, backend.closed.getCount());
            assertFalse(source.isRecording());
        }
    }

    @Test
    void runtimeFailureCanRestart() throws Exception {
        IllegalStateException lost = new IllegalStateException("output lost");
        Backend broken = new Backend() {
            @Override
            public void pump(CustomAudioSource source) {
                throw lost;
            }
        };
        AtomicInteger attempts = new AtomicInteger();

        try (SystemAudioSource source = new SystemAudioSource(
                () -> attempts.getAndIncrement() == 0 ? broken : new Backend())) {
            source.start();

            assertTrue(broken.closed.await(5, TimeUnit.SECONDS));

            // The join also waits for the failure to be published.
            source.stop();
            assertSame(lost, source.getFailure());

            source.start();
            assertNull(source.getFailure());
            assertTrue(source.isRecording());
        }
    }

    @Test
    void pushFromOtherThreadRejected() throws Exception {
        Backend backend = new Backend();

        try (SystemAudioSource source = new SystemAudioSource(() -> backend)) {
            // Pushed audio would interleave with the captured audio.
            assertThrows(IllegalStateException.class,
                    () -> source.pushAudio(CHUNK, 16, 48000, 2, 480));

            source.start();
            assertTrue(backend.pumped.await(5, TimeUnit.SECONDS));

            assertThrows(IllegalStateException.class,
                    () -> source.pushAudio(CHUNK, 16, 48000, 2, 480));
            assertThrows(IllegalStateException.class,
                    () -> source.pushAudio(CHUNK, 16, 48000, 2, 480, 1));
            assertTrue(source.isRecording());
        }
    }

    @Test
    void callbackCanDisposeSource() throws Exception {
        AtomicReference<SystemAudioSource> ref = new AtomicReference<>();
        Backend backend = new Backend() {
            @Override
            public void pump(CustomAudioSource source) {
                ref.get().dispose();
                super.pump(source);
            }
        };
        SystemAudioSource source = new SystemAudioSource(() -> backend);
        ref.set(source);

        try {
            source.start();

            assertTrue(backend.closed.await(5, TimeUnit.SECONDS));

            source.stop();
            assertThrows(IllegalStateException.class, source::start);
        }
        finally {
            source.dispose();
        }
    }

    @Test
    void disposeDuringNativeCallback() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch disposing = new CountDownLatch(1);
        Backend backend = new Backend() {
            @Override
            public void pump(CustomAudioSource source) {
                source.pushAudio(CHUNK, 16, 48000, 2, 480);
            }
        };
        SystemAudioSource source = new SystemAudioSource(() -> backend);
        AudioTrack track = factory.createAudioTrack("system-callback", source);
        CompletableFuture<Void> disposed = new CompletableFuture<>();

        try {
            track.addSink((data, bits, rate, channels, frames) -> {
                entered.countDown();

                try {
                    if (!disposing.await(5, TimeUnit.SECONDS)) {
                        return;
                    }
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                source.dispose();
            });

            source.start();

            assertTrue(entered.await(5, TimeUnit.SECONDS));

            Thread closer = new Thread(() -> {
                // Hold the lifecycle monitor before allowing the native sink
                // callback to request disposal on the capture worker as well.
                synchronized (source) {
                    disposing.countDown();

                    try {
                        source.dispose();
                        disposed.complete(null);
                    }
                    catch (Throwable e) {
                        disposed.completeExceptionally(e);
                    }
                }
            });
            closer.setDaemon(true);
            closer.start();

            disposed.get(5, TimeUnit.SECONDS);

            assertFalse(source.isRecording());
            assertNull(source.getFailure());
        }
        finally {
            disposing.countDown();
            source.dispose();
            track.dispose();
        }
    }

    @Test
    void sourceFeedsNativeTrack() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        Backend backend = new Backend() {
            @Override
            public void pump(CustomAudioSource source) {
                source.pushAudio(CHUNK, 16, 48000, 2, 480);
            }
        };

        try (SystemAudioSource source = new SystemAudioSource(() -> backend)) {
            AudioTrack track = factory.createAudioTrack("system", source);

            try {
                track.addSink((data, bits, rate, channels, frames) -> {
                    if (bits == 16 && rate == 48000 && channels == 2 && frames == 480) {
                        received.countDown();
                    }
                });

                source.start();

                assertTrue(received.await(5, TimeUnit.SECONDS));

                source.stop();
            }
            finally {
                track.dispose();
            }
        }
    }


    /**
     * Stands in for the platform capture and checks that it is only used on
     * the thread that opened it.
     */
    private static class Backend implements SystemAudioSource.Backend {

        final CountDownLatch pumped = new CountDownLatch(1);

        final CountDownLatch closed = new CountDownLatch(1);

        Thread thread;


        @Override
        public void initialize() {
            thread = Thread.currentThread();
        }

        @Override
        public void pump(CustomAudioSource source) {
            assertSame(thread, Thread.currentThread());
            pumped.countDown();
        }

        @Override
        public void dispose() {
            assertSame(thread, Thread.currentThread());
            closed.countDown();
        }

    }

}
