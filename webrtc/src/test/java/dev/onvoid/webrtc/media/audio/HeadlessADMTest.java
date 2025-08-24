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

import java.util.List;

import dev.onvoid.webrtc.PeerConnectionFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
public class HeadlessADMTest {

    private HeadlessAudioDeviceModule adm;


    @BeforeEach
    void setUp() {
        adm = new HeadlessAudioDeviceModule();
    }

    @AfterEach
    void tearDown() {
        if (adm != null) {
            try {
                adm.stopPlayout();
            }
            catch (Throwable ignored) {
                // Ignore any exceptions during stopPlayout, as it may not be initialized.
            }
            try {
                adm.stopRecording();
            }
            catch (Throwable ignored) {
                // Ignore any exceptions during stopRecording, as it may not be initialized.
            }
            adm.dispose();
            adm = null;
        }
    }

    @Test
    void getPlayoutDevices_returnsAtLeastOneDevice() {
        List<AudioDevice> devices = adm.getPlayoutDevices();
        assertNotNull(devices, "Playout devices list should not be null");
        assertFalse(devices.isEmpty(), "Headless module should expose at least one dummy playout device");
    }

    @Test
    void getRecordingDevices_returnsAtLeastOneDevice() {
        List<AudioDevice> devices = adm.getRecordingDevices();
        assertNotNull(devices, "Recording devices list should not be null");
        assertFalse(devices.isEmpty(), "Headless module should expose at least one dummy recording device");
    }

    @Test
    void startStopPlayout_afterInit_doesNotThrow() {
        assertDoesNotThrow(() -> {
            adm.initPlayout();
            adm.startPlayout();
            adm.stopPlayout();
        });
    }

    @Test
    void startStopRecording_afterInit_doesNotThrow() {
        assertDoesNotThrow(() -> {
            adm.initRecording();
            adm.startRecording();
            adm.stopRecording();
        });
    }

    @Test
    void startPlayout_withoutInit_throwsException() {
        assertThrows(Error.class, () -> adm.startPlayout());
    }

    @Test
    void startRecording_withoutInit_throwsException() {
        assertThrows(Error.class, () -> adm.startRecording());
    }

	@Test
	void initPeerConnectionFactory() {
		PeerConnectionFactory factory = new PeerConnectionFactory(adm);
		factory.dispose();
	}
}
