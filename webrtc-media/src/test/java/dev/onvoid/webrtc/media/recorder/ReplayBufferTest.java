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

package dev.onvoid.webrtc.media.recorder;

import static dev.onvoid.webrtc.media.recorder.SegmentedRecorderTest.assertPlaysOnItsOwn;
import static dev.onvoid.webrtc.media.recorder.SegmentedRecorderTest.readInfo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;
import dev.onvoid.webrtc.media.player.MediaInfo;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Tests keeping the last seconds of a call and saving them, checked by
 * reading the replays back.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ReplayBufferTest {

	private AudioDeviceModule audioModule;
	private PeerConnectionFactory factory;

	@TempDir
	Path tempDir;


	@BeforeAll
	void initFactory() {
		audioModule = new AudioDeviceModule(AudioLayer.kDummyAudio);
		factory = new PeerConnectionFactory(audioModule);
	}

	@AfterAll
	void disposeFactory() {
		factory.dispose();
		audioModule.dispose();
	}

	@Test
	void savesLastSeconds() throws Exception {
		SegmentedRecorderTest.Listener listener = new SegmentedRecorderTest.Listener();

		try (TestCall call = new TestCall(factory);
				ReplayBuffer buffer = new ReplayBuffer(Duration.ofSeconds(3))) {
			buffer.setListener(listener);
			buffer.addTrack(call.getVideoSender());
			buffer.addTrack(call.getAudioSender());
			buffer.start();

			assertEquals(MediaRecorderState.RECORDING, buffer.getState());
			assertTrue(listener.started.await(10, TimeUnit.SECONDS), "not started");

			Thread.sleep(8000);

			Path first = tempDir.resolve("replay-1.mkv");
			MediaRecorderSegment replay = buffer.save(first).get(10, TimeUnit.SECONDS);

			assertEquals(0, replay.getIndex());
			assertEquals(first, replay.getFile());
			assertEquals(MediaRecorderResult.COMPLETE, replay.getResult());
			assertEquals(Files.size(first), replay.getSize());

			// From the key frame before the window, which the buffer asks for
			// every two seconds at a window of three.
			long durationMs = replay.getDuration().toMillis();

			assertTrue(durationMs >= 2900 && durationMs < 6000, replay.toString());
			assertTrue(replay.getStart().toMillis() > 2000, replay.toString());

			assertPlaysOnItsOwn(first, 2);

			MediaInfo info = readInfo(first);

			assertEquals("vp8", info.getVideoCodec());
			assertEquals("opus", info.getAudioCodec());

			// The buffer goes on, and saves again.
			Thread.sleep(1000);

			Path second = tempDir.resolve("replay-2.webm");
			MediaRecorderSegment next = buffer.save(second).get(10, TimeUnit.SECONDS);

			assertEquals(1, next.getIndex());
			assertTrue(next.getStart().compareTo(replay.getStart()) >= 0, next.toString());
			assertPlaysOnItsOwn(second, 2);
		}

		assertNull(listener.error.get());
		assertTrue(listener.warnings.isEmpty(), listener.warnings.toString());
		assertTrue(listener.segments.isEmpty());
	}

	@Test
	void limitsWhatItHolds() throws Exception {
		try (TestCall call = new TestCall(factory);
				ReplayBuffer buffer = new ReplayBuffer(Duration.ofSeconds(10), 50_000)) {
			buffer.addTrack(call.getVideoSender());
			buffer.addTrack(call.getAudioSender());
			buffer.start();

			Thread.sleep(6000);

			Path file = tempDir.resolve("limited.mkv");
			MediaRecorderSegment replay = buffer.save(file).get(10, TimeUnit.SECONDS);

			// Far shorter than the six seconds the window would hold.
			assertTrue(replay.getDuration().toMillis() < 4000, replay.toString());
			assertTrue(replay.getSize() < 100_000, replay.toString());
		}
	}

	@Test
	void keepsNewCodecAfterChange() throws Exception {
		SegmentedRecorderTest.Listener listener = new SegmentedRecorderTest.Listener();

		try (TestCall call = new TestCall(factory);
				ReplayBuffer buffer = new ReplayBuffer(Duration.ofSeconds(2))) {
			buffer.setListener(listener);
			buffer.addTrack(call.getVideoSender());
			buffer.start();

			assertTrue(listener.started.await(10, TimeUnit.SECONDS), "not started");

			Thread.sleep(1500);

			call.switchVideoCodec("VP9");

			Thread.sleep(4000);

			Path file = tempDir.resolve("changed.mkv");

			buffer.save(file).get(10, TimeUnit.SECONDS);

			assertEquals("vp9", readInfo(file).getVideoCodec());
			assertPlaysOnItsOwn(file, 1);
		}

		assertTrue(listener.warnings.stream().anyMatch(warning -> warning.contains("changed its codec")),
				listener.warnings.toString());
	}

	@Test
	void stopSavesWhatWasAskedFor() throws Exception {
		try (TestCall call = new TestCall(factory);
				ReplayBuffer buffer = new ReplayBuffer(Duration.ofSeconds(2))) {
			SegmentedRecorderTest.Listener listener = new SegmentedRecorderTest.Listener();

			buffer.setListener(listener);
			buffer.addTrack(call.getVideoSender());
			buffer.start();

			assertTrue(listener.started.await(10, TimeUnit.SECONDS), "not started");

			Thread.sleep(1000);

			Path file = tempDir.resolve("at-stop.mkv");

			java.util.concurrent.CompletableFuture<MediaRecorderSegment> saved = buffer.save(file);

			buffer.stop();

			assertEquals(MediaRecorderState.STOPPED, buffer.getState());
			assertEquals(file, saved.get(10, TimeUnit.SECONDS).getFile());
			assertPlaysOnItsOwn(file, 1);

			assertThrows(IllegalStateException.class, () -> buffer.save(tempDir.resolve("after-stop.mkv")));
			assertTrue(!Files.exists(tempDir.resolve("after-stop.mkv")));
		}
	}

	@Test
	void refusesFileThatExists() throws Exception {
		byte[] earlier = "earlier recording".getBytes(StandardCharsets.UTF_8);
		Path file = Files.write(tempDir.resolve("exists.mkv"), earlier);

		try (TestCall call = new TestCall(factory);
				ReplayBuffer buffer = new ReplayBuffer(Duration.ofSeconds(2))) {
			buffer.addTrack(call.getAudioSender());
			buffer.start();

			assertThrows(FileAlreadyExistsException.class, () -> buffer.save(file));
		}

		assertArrayEquals(earlier, Files.readAllBytes(file));
	}

	@Test
	void refusesSaveBeforeStart() throws Exception {
		try (TestCall call = new TestCall(factory);
				ReplayBuffer buffer = new ReplayBuffer(Duration.ofSeconds(2))) {
			buffer.addTrack(call.getAudioSender());

			assertThrows(IllegalStateException.class, () -> buffer.save(tempDir.resolve("early.mkv")));
			assertTrue(!Files.exists(tempDir.resolve("early.mkv")));
		}
	}

	@Test
	void refusesBadLimits() {
		assertThrows(IllegalArgumentException.class, () -> new ReplayBuffer(Duration.ofMillis(500)));
		assertThrows(IllegalArgumentException.class, () -> new ReplayBuffer(Duration.ofSeconds(5), 0));
	}

}
