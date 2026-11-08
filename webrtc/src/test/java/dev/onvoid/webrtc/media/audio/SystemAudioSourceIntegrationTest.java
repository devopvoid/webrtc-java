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

import dev.onvoid.webrtc.TestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Opt in with -Dtest.systemAudio=true while playing audio on the default output. */
@EnabledIfSystemProperty(named = "test.systemAudio", matches = "true")
class SystemAudioSourceIntegrationTest extends TestBase {
    @Test
    void capturesOutputAndRestarts() throws Exception {
        try (SystemAudioSource source = new SystemAudioSource()) {
            AudioTrack track = factory.createAudioTrack("system", source);
            AtomicReference<CountDownLatch> received = new AtomicReference<>(new CountDownLatch(5));
            try {
                track.addSink((data, bits, rate, channels, frames) -> {
                    if (bits == 16 && rate == 48000 && channels == 2 && frames == 480) {
                        for (byte sample : data) {
                            if (sample != 0) { received.get().countDown(); break; }
                        }
                    }
                });
                source.start();
                assertTrue(received.get().await(10, TimeUnit.SECONDS), "Play audio on the default output");
                source.stop();
                assertNull(source.getFailure());
                assertFalse(source.isRecording());
                received.set(new CountDownLatch(5));
                source.start();
                assertTrue(received.get().await(10, TimeUnit.SECONDS), "No audio after restart");
                assertTrue(source.isRecording());
                source.stop();
                assertNull(source.getFailure());
            }
            finally { track.dispose(); }
        }
    }
}
