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

package dev.onvoid.webrtc.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import dev.onvoid.webrtc.media.AudioBackendUnavailableException;
import dev.onvoid.webrtc.media.Device;
import dev.onvoid.webrtc.media.DeviceChangeListener;
import dev.onvoid.webrtc.media.MediaDevices;

import org.junit.jupiter.api.Test;

class DeviceBackendTests {

	@Test
	void unavailablePulseAudioIsTyped() throws Exception {
		assumeTrue(System.getProperty("os.name").equals("Linux"));
		ProcessBuilder builder = new ProcessBuilder(
				Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
				"-cp", StandaloneLoadingTests.classPath(), UnavailableBackend.class.getName());
		// A nonexistent Unix socket makes connection failure deterministic,
		// irrespective of the developer's running PulseAudio server.
		builder.environment().put("PULSE_SERVER", "unix:/nonexistent/webrtc-java-test-pulse");
		Process process = builder.redirectErrorStream(true).start();
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		Thread reader = new Thread(() -> StandaloneLoadingTests.copy(process.getInputStream(), output));
		reader.start();
		try {
			assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Backend probe timed out");
			reader.join();
			assertEquals(0, process.exitValue(), new String(output.toByteArray(), StandardCharsets.UTF_8));
		}
		finally {
			process.destroyForcibly();
		}
	}

	public static class UnavailableBackend {

		public static void main(String[] args) {
			expectUnavailable(MediaDevices::getAudioCaptureDevices);
			expectUnavailable(MediaDevices::getAudioRenderDevices);
			expectUnavailable(MediaDevices::getDefaultAudioCaptureDevice);
			expectUnavailable(MediaDevices::getDefaultAudioRenderDevice);
			expectUnavailable(() -> MediaDevices.addDeviceChangeListener(new DeviceChangeListener() {
				@Override
				public void deviceConnected(Device device) { }
				@Override
				public void deviceDisconnected(Device device) { }
			}));
		}

		private static void expectUnavailable(Runnable operation) {
			try {
				operation.run();
			}
			catch (AudioBackendUnavailableException expected) {
				return;
			}
			throw new AssertionError("Expected an unavailable audio backend");
		}
	}
}
