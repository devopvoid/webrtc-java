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
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.onvoid.webrtc.media.AudioBackendUnavailableException;
import dev.onvoid.webrtc.media.Device;
import dev.onvoid.webrtc.media.DeviceChangeListener;
import dev.onvoid.webrtc.media.MediaDevices;
import dev.onvoid.webrtc.media.audio.AudioDevice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeviceBackendTests {

	private static final String SINK = "webrtc_java_sink";

	private static final String SOURCE = "webrtc_java_source";


	@Test
	void unavailablePulseAudioIsTyped() throws Exception {
		assumeTrue(isLinux());
		// A nonexistent Unix socket makes connection failure deterministic,
		// irrespective of the developer's running PulseAudio server.
		runProbe(UnavailableBackend.class, Collections.singletonMap(
				"PULSE_SERVER", "unix:/nonexistent/webrtc-java-test-pulse"));
	}

	/**
	 * Issue #580: the default device is removed while an application asks for
	 * the default devices from a device change listener.
	 */
	@Test
	void removedDefaultDeviceDoesNotCrash(@TempDir Path dir) throws Exception {
		assumeTrue(isLinux());
		assumeTrue(onPath("pulseaudio") && onPath("pactl"), "no PulseAudio server and pactl");

		// A server of its own, with no hardware, whose only sink and source are
		// the defaults the probe removes.
		Path socket = dir.resolve("native");
		ProcessBuilder builder = new ProcessBuilder("pulseaudio", "-n", "--daemonize=no",
				"--exit-idle-time=-1", "--use-pid-file=no", "--log-target=stderr",
				"--load=module-null-sink sink_name=" + SINK,
				"--load=module-null-source source_name=" + SOURCE,
				// Last, so that the socket appears once the devices are there.
				"--load=module-native-protocol-unix auth-anonymous=1 socket=" + socket);
		builder.environment().put("HOME", dir.toString());
		builder.environment().put("XDG_RUNTIME_DIR", dir.toString());
		builder.environment().put("XDG_CONFIG_HOME", dir.toString());
		// Keep it off the session bus of a desktop the tests may run on.
		builder.environment().put("DBUS_SESSION_BUS_ADDRESS", "unix:path=" + dir.resolve("no-bus"));
		Process server = builder.redirectErrorStream(true)
				.redirectOutput(dir.resolve("pulseaudio.log").toFile())
				.start();
		try {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
			while (!Files.exists(socket)) {
				assertTrue(server.isAlive(), () -> "PulseAudio did not start:\n" + log(dir));
				assertTrue(System.nanoTime() < deadline, () -> "PulseAudio did not listen:\n" + log(dir));
				Thread.sleep(50);
			}
			runProbe(RemovedDefaultDevice.class, Collections.singletonMap(
					"PULSE_SERVER", "unix:" + socket));
		}
		finally {
			server.destroy();
			if (!server.waitFor(10, TimeUnit.SECONDS)) {
				server.destroyForcibly();
			}
		}
	}

	private static void runProbe(Class<?> probe, Map<String, String> environment) throws Exception {
		ProcessBuilder builder = new ProcessBuilder(
				Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
				"-cp", StandaloneLoadingTests.classPath(), probe.getName());
		builder.environment().putAll(environment);
		Process process = builder.redirectErrorStream(true).start();
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		Thread reader = new Thread(() -> StandaloneLoadingTests.copy(process.getInputStream(), output));
		reader.start();
		try {
			assertTrue(process.waitFor(30, TimeUnit.SECONDS), probe.getSimpleName() + " timed out");
			reader.join();
			assertEquals(0, process.exitValue(), new String(output.toByteArray(), StandardCharsets.UTF_8));
		}
		finally {
			process.destroyForcibly();
		}
	}

	private static boolean isLinux() {
		return System.getProperty("os.name").equals("Linux");
	}

	private static boolean onPath(String program) {
		String path = System.getenv("PATH");

		if (path == null) {
			return false;
		}
		for (String dir : path.split(File.pathSeparator)) {
			if (Files.isExecutable(Paths.get(dir, program))) {
				return true;
			}
		}
		return false;
	}

	private static String log(Path dir) {
		try {
			return new String(Files.readAllBytes(dir.resolve("pulseaudio.log")), StandardCharsets.UTF_8);
		}
		catch (Exception e) {
			return e.toString();
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



	public static class RemovedDefaultDevice {

		public static void main(String[] args) {
			// PulseAudio's thread stays attached to the JVM once it has called
			// the listener, and keeps the JVM from ending on its own.
			try {
				probe();
			}
			catch (Throwable e) {
				e.printStackTrace();
				System.exit(1);
			}
			System.exit(0);
		}

		private static void probe() throws Exception {
			// Known devices are the ones whose removal is reported.
			MediaDevices.getAudioRenderDevices();
			MediaDevices.getAudioCaptureDevices();

			expectDefault(SINK, MediaDevices.getDefaultAudioRenderDevice());
			expectDefault(SOURCE, MediaDevices.getDefaultAudioCaptureDevice());

			CountDownLatch removed = new CountDownLatch(2);

			MediaDevices.addDeviceChangeListener(new DeviceChangeListener() {
				@Override
				public void deviceConnected(Device device) { }
				@Override
				public void deviceDisconnected(Device device) {
					// The listener runs on PulseAudio's own thread, which does
					// not wait for the answers: these ask for the device just
					// removed, and the server answers that it does not exist.
					MediaDevices.getDefaultAudioRenderDevice();
					MediaDevices.getDefaultAudioCaptureDevice();
					removed.countDown();
				}
			});

			pactl("unload-module", "module-null-sink");
			pactl("unload-module", "module-null-source");

			if (!removed.await(10, TimeUnit.SECONDS)) {
				throw new AssertionError("The removals were not reported");
			}

			// Answered after the failed queries, on the same connection: the
			// server has no device left to name the default.
			expectDefault(null, MediaDevices.getDefaultAudioRenderDevice());
			expectDefault(null, MediaDevices.getDefaultAudioCaptureDevice());
		}

		private static void expectDefault(String descriptor, AudioDevice device) {
			String actual = device == null ? null : device.getDescriptor();

			if (descriptor == null ? actual != null : !descriptor.equals(actual)) {
				throw new AssertionError("Expected the default " + descriptor + ", got " + device);
			}
		}

		private static void pactl(String... args) throws Exception {
			String[] command = new String[args.length + 1];
			command[0] = "pactl";
			System.arraycopy(args, 0, command, 1, args.length);

			Process process = new ProcessBuilder(command).inheritIO().start();

			if (process.waitFor() != 0) {
				throw new AssertionError("pactl " + String.join(" ", args) + " failed");
			}
		}
	}
}
