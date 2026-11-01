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

package dev.onvoid.webrtc.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import dev.onvoid.webrtc.media.DeviceChangeListener;
import dev.onvoid.webrtc.media.audio.AudioDevice;
import dev.onvoid.webrtc.media.video.VideoDevice;

import org.junit.jupiter.api.Test;

/**
 * Drives {@link DevicesImpl} with a source of devices the test controls: when
 * devices change, and how a query fails.
 */
class DevicesTests extends ClientTestBase {

	@Test
	void changeDuringFirstRead() throws Exception {
		// A camera is plugged in after the system answered the first read, but
		// before that read returned. The change queues a read behind it.
		ManualExecutor control = new ManualExecutor();
		WebRtc runtime = WebRtc.builder()
				.audio(AudioMode.HEADLESS)
				.errorHandler(errors::add)
				.controlPool(control)
				.build();
		TestSource source = new TestSource();
		source.cameras.add(camera("Built-in"));
		source.duringCameraRead = () -> source.connect(camera("Plugged"));

		try {
			DevicesImpl devices = new DevicesImpl((LocalMediaImpl) runtime.media(), null, source);
			CompletableFuture<List<String>> notified = new CompletableFuture<>();
			devices.onChange(changed -> notified.complete(names(changed.cameras())));

			// The snapshot predates the change.
			assertEquals(Arrays.asList("Built-in"), names(devices.cameras()));

			control.runAll();

			assertEquals(Arrays.asList("Built-in", "Plugged"), names(devices.cameras()));
			assertEquals(Arrays.asList("Built-in", "Plugged"), await(notified));

			devices.dispose();
			assertTrue(source.listeners.isEmpty());
		}
		finally {
			CompletableFuture<Void> closed = runtime.closeAsync();
			control.runUntil(closed::isDone, TIMEOUT_SECONDS);
			await(closed);
		}
	}

	@Test
	void platformFailureIsEmpty() {
		// What the native layer throws without an audio system.
		TestSource source = new TestSource();
		source.microphoneFailure = new Error("PulseAudio: Library not found on system.");

		DevicesImpl devices = devices(source);

		assertEquals(Collections.emptyList(), devices.microphones());
	}

	@Test
	void failedQueryThrows() {
		TestSource source = new TestSource();
		source.cameraFailure = new IllegalStateException("enumeration failed");

		DevicesImpl devices = devices(source);

		MediaException first = assertThrows(MediaException.class, devices::cameras);
		MediaException second = assertThrows(MediaException.class, devices::cameras);

		assertSame(source.cameraFailure, first.getCause());
		assertTrue(first.getMessage().contains("enumeration failed"), first::getMessage);
		assertNotSame(first, second);
	}

	@Test
	void jvmErrorPassesThrough() {
		// The first read fails, after the listener was registered.
		TestSource source = new TestSource();
		source.cameraFailure = new OutOfMemoryError("test");

		OutOfMemoryError e = assertThrows(OutOfMemoryError.class, () -> devices(source));

		assertSame(source.cameraFailure, e);
		assertTrue(source.listeners.isEmpty());
	}

	@Test
	void jvmErrorPassesThroughUnwatched() {
		TestSource source = new TestSource();
		source.watchFailure = new Error("PulseAudio: Could not connect to the context");
		source.cameraFailure = new UnsatisfiedLinkError("getVideoCaptureDevices");

		DevicesImpl devices = devices(source);

		assertSame(source.cameraFailure, assertThrows(UnsatisfiedLinkError.class, devices::cameras));
	}

	@Test
	void unwatchedReadsEachCall() {
		// The system reports no changes, as Linux without PulseAudio.
		TestSource source = new TestSource();
		source.watchFailure = new Error("PulseAudio: Library not found on system.");

		DevicesImpl devices = devices(source);
		source.cameras.add(camera("Plugged"));

		assertEquals(Arrays.asList("Plugged"), names(devices.cameras()));
		assertEquals(1, source.cameraReads);
	}

	@Test
	void failedWatchReported() {
		TestSource source = new TestSource();
		source.watchFailure = new IllegalStateException("no listener slot");

		DevicesImpl devices = devices(source);

		assertEquals(1, errors.size(), () -> "Errors: " + errors);
		Throwable reported = errors.remove(0);
		assertInstanceOf(MediaException.class, reported);
		assertSame(source.watchFailure, reported.getCause());

		// Read on each call instead.
		source.cameras.add(camera("Plugged"));
		assertEquals(Arrays.asList("Plugged"), names(devices.cameras()));
	}

	@Test
	void jvmErrorInWatch() {
		TestSource source = new TestSource();
		source.watchFailure = new NoClassDefFoundError("dev/onvoid/webrtc/media/MediaDevices");

		assertSame(source.watchFailure, assertThrows(NoClassDefFoundError.class, () -> devices(source)));
	}

	private DevicesImpl devices(TestSource source) {
		return new DevicesImpl((LocalMediaImpl) rtc.media(), null, source);
	}

	private static List<String> names(List<VideoDevice> devices) {
		return devices.stream().map(VideoDevice::getName).collect(Collectors.toList());
	}

	private static VideoDevice camera(String name) {
		return new VideoDevice(name, name.toLowerCase().replace(' ', '-')) { };
	}

	/**
	 * Throws what a test sets, as a native method may: also a checked
	 * exception, or an error.
	 */
	@SuppressWarnings("unchecked")
	private static <E extends Throwable> void raise(Throwable failure) throws E {
		if (failure != null) {
			throw (E) failure;
		}
	}


	private static final class TestSource implements DevicesImpl.Source {

		final List<VideoDevice> cameras = new CopyOnWriteArrayList<>();

		final List<DeviceChangeListener> listeners = new CopyOnWriteArrayList<>();

		Throwable cameraFailure;

		Throwable microphoneFailure;

		Throwable watchFailure;

		/** Runs once, after the cameras were read and before they are returned. */
		Runnable duringCameraRead;

		int cameraReads;


		@Override
		public List<VideoDevice> cameras() {
			cameraReads++;
			DevicesTests.<RuntimeException>raise(cameraFailure);

			List<VideoDevice> answer = new ArrayList<>(cameras);
			Runnable during = duringCameraRead;

			duringCameraRead = null;

			if (during != null) {
				during.run();
			}

			return answer;
		}

		@Override
		public List<AudioDevice> microphones() {
			DevicesTests.<RuntimeException>raise(microphoneFailure);
			return Collections.emptyList();
		}

		@Override
		public List<AudioDevice> speakers() {
			return Collections.emptyList();
		}

		@Override
		public void watch(DeviceChangeListener listener) {
			DevicesTests.<RuntimeException>raise(watchFailure);
			listeners.add(listener);
		}

		@Override
		public void unwatch(DeviceChangeListener listener) {
			listeners.remove(listener);
		}

		void connect(VideoDevice camera) {
			cameras.add(camera);
			listeners.forEach(listener -> listener.deviceConnected(camera));
		}
	}
}
