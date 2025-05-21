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

package dev.onvoid.webrtc.media.audio;

import dev.onvoid.webrtc.TestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AudioTrackTests extends TestBase {

    private AudioTrack audioTrack;


    @BeforeEach
    void init() {
        AudioOptions audioOptions = new AudioOptions();
        AudioTrackSource audioSource = factory.createAudioSource(audioOptions);

        audioTrack = factory.createAudioTrack("audioTrack", audioSource);
    }

    @AfterEach
    void dispose() {
        audioTrack.dispose();
    }

    @Test
    void disableEnableTrack() {
        audioTrack.setEnabled(false);

        assertFalse(audioTrack.isEnabled());

        audioTrack.setEnabled(true);

        assertTrue(audioTrack.isEnabled());
    }

    @Test
    void addNullSink() {
        assertThrows(NullPointerException.class, () -> audioTrack.addSink(null));
    }

    @Test
    void removeNullSink() {
        assertThrows(NullPointerException.class, () -> audioTrack.removeSink(null));
    }

    @Test
    void addRemoveSink() {
        AudioTrackSink sink = (data, bitsPerSample, sampleRate, channels, frames) -> {
        };

        audioTrack.addSink(sink);
        audioTrack.removeSink(sink);
    }

    @Test
    void getSource() {
        assertNotNull(audioTrack.getSource());
    }
}
