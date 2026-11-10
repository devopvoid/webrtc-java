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
import static dev.onvoid.webrtc.media.recorder.SegmentedRecorderTest.assertSegments;
import static dev.onvoid.webrtc.media.recorder.SegmentedRecorderTest.assertStreams;
import static dev.onvoid.webrtc.media.recorder.SegmentedRecorderTest.audio;
import static dev.onvoid.webrtc.media.recorder.SegmentedRecorderTest.millis;
import static dev.onvoid.webrtc.media.recorder.SegmentedRecorderTest.readInfo;
import static dev.onvoid.webrtc.media.recorder.SegmentedRecorderTest.seconds;
import static dev.onvoid.webrtc.media.recorder.SegmentedRecorderTest.video;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;
import dev.onvoid.webrtc.media.player.MediaInfo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Tests keeping the last seconds of a call and saving them.
 * <p>
 * As in {@link SegmentedRecorderTest}, most tests feed the buffer frames of
 * their own on a clock of their own, so that what a replay holds follows from
 * the frames alone, and is checked to the frame. One test keeps a real call.
 */
@Execution(ExecutionMode.SAME_THREAD)
class ReplayBufferTest {

	@TempDir
	Path tempDir;


	@Test
	void savesLastSeconds() throws Exception {
		SegmentedRecorderTest.Listener listener = new SegmentedRecorderTest.Listener();

		try (FakeTrack video = FakeTrack.vp8();
				FakeTrack audio = FakeTrack.opus();
				ReplayBuffer buffer = buffer(Duration.ofSeconds(3))) {
			FakeFeed feed = new FakeFeed(buffer::sync, video, audio);

			buffer.setListener(listener);
			video.addTo(buffer);
			audio.addTo(buffer);
			buffer.start();

			assertEquals(MediaRecorderState.RECORDING, buffer.getState());

			feed.play(seconds(8));

			// At a window of three seconds the buffer asks for a key frame
			// every two, and gets them at 2, 4 and 6 s. The window begins at
			// 4.98 s, so the replay begins at the key frame at 4 s, before it.
			Path first = tempDir.resolve("replay-1.mkv");
			MediaRecorderSegment replay = save(buffer, first);

			assertEquals(0, replay.getIndex());
			assertEquals(first, replay.getFile());
			assertEquals(MediaRecorderResult.COMPLETE, replay.getResult());
			assertEquals(Files.size(first), replay.getSize());
			assertSegments(List.of(replay), 4000, 3980);
			assertStreams(first, video(100, 0, 3960), audio(200, 0, 3980));

			MediaInfo info = readInfo(first);

			assertEquals("vp8", info.getVideoCodec());
			assertEquals("opus", info.getAudioCodec());

			// The buffer goes on, and saves again: from the key frame at 4 s
			// still, the last one before the window, which now begins at
			// 5.98 s.
			feed.play(seconds(1));

			Path second = tempDir.resolve("replay-2.webm");
			MediaRecorderSegment next = save(buffer, second);

			assertEquals(1, next.getIndex());
			assertSegments(List.of(next), 4000, 4980);
			assertStreams(second, video(125, 0, 4960), audio(250, 0, 4980));
			assertEquals(4, video.getKeyFrameRequests());

			buffer.sync();
		}

		assertNull(listener.error.get());
		assertEquals(Collections.emptyList(), listener.warnings);
		assertTrue(listener.segments.isEmpty());
	}

	@Test
	void savesAudioFromTheWindow() throws Exception {
		try (FakeTrack audio = FakeTrack.opus();
				ReplayBuffer buffer = buffer(Duration.ofSeconds(2))) {
			FakeFeed feed = new FakeFeed(buffer::sync, audio);

			audio.addTo(buffer);
			buffer.start();

			feed.play(seconds(5));

			// Without video, a replay begins where the window does.
			Path file = tempDir.resolve("audio.mkv");
			MediaRecorderSegment replay = save(buffer, file);

			assertSegments(List.of(replay), 2980, 2000);
			assertStreams(file, audio(101, 0, 2000));
		}
	}

	@Test
	void limitsWhatItHolds() throws Exception {
		long maxBytes = 50_000;

		try (FakeTrack video = FakeTrack.vp8();
				FakeTrack audio = FakeTrack.opus();
				ReplayBuffer buffer = new ReplayBuffer(Duration.ofSeconds(10), maxBytes, FakeMedia.tableAddress())) {
			FakeFeed feed = new FakeFeed(buffer::sync, video, audio);

			video.addTo(buffer);
			audio.addTo(buffer);
			buffer.start();

			feed.play(seconds(6));

			// About 30 kB a second: far less than the six seconds the window
			// would hold, from a key frame the buffer asked for once it had to
			// let go of the one before.
			Path file = tempDir.resolve("limited.mkv");
			MediaRecorderSegment replay = save(buffer, file);

			assertTrue(replay.getDuration().compareTo(Duration.ofSeconds(2)) < 0, replay.toString());
			assertTrue(replay.getStart().compareTo(Duration.ofSeconds(4)) > 0, replay.toString());
			assertPlaysOnItsOwn(file, false);
		}
	}

	@Test
	void keepsNewCodecAfterChange() throws Exception {
		SegmentedRecorderTest.Listener listener = new SegmentedRecorderTest.Listener();

		try (FakeTrack video = FakeTrack.vp8();
				ReplayBuffer buffer = buffer(Duration.ofSeconds(2))) {
			FakeFeed feed = new FakeFeed(buffer::sync, video);

			buffer.setListener(listener);
			video.addTo(buffer);
			buffer.start();

			feed.playUntil(seconds(3));
			video.switchCodec("video/VP9");
			feed.playUntil(seconds(6));

			// The frames in VP8 are gone; the replay begins at the first in
			// VP9, the last key frame before the window.
			Path file = tempDir.resolve("changed.mkv");
			MediaRecorderSegment replay = save(buffer, file);

			assertSegments(List.of(replay), 3000, 2960);
			assertStreams(file, video(75, 0, 2960));
			assertEquals("vp9", readInfo(file).getVideoCodec());

			buffer.sync();
		}

		assertEquals(List.of("Track 0 changed its codec from video/VP8 to video/VP9"), listener.warnings);
	}

	@Test
	void stopSavesWhatWasAskedFor() throws Exception {
		try (FakeTrack video = FakeTrack.vp8();
				ReplayBuffer buffer = buffer(Duration.ofSeconds(2))) {
			FakeFeed feed = new FakeFeed(buffer::sync, video);

			video.addTo(buffer);
			buffer.start();

			feed.play(seconds(1));

			Path file = tempDir.resolve("at-stop.mkv");
			CompletableFuture<MediaRecorderSegment> saved = buffer.save(file);

			buffer.stop();

			assertEquals(MediaRecorderState.STOPPED, buffer.getState());

			MediaRecorderSegment replay = saved.get(10, TimeUnit.SECONDS);

			assertEquals(file, replay.getFile());
			assertSegments(List.of(replay), 0, 960);
			assertStreams(file, video(25, 0, 960));

			assertThrows(IllegalStateException.class, () -> buffer.save(tempDir.resolve("after-stop.mkv")));
			assertFalse(Files.exists(tempDir.resolve("after-stop.mkv")));
			assertEquals(0, video.observers());
		}
	}

	@Test
	void emptyBufferSavesNothing() throws Exception {
		try (FakeTrack video = FakeTrack.vp8();
				ReplayBuffer buffer = buffer(Duration.ofSeconds(2))) {
			FakeFeed feed = new FakeFeed(buffer::sync);

			video.addTo(buffer);
			buffer.start();

			feed.play(seconds(1));

			Path file = tempDir.resolve("nothing.mkv");
			CompletableFuture<MediaRecorderSegment> saved = buffer.save(file);

			ExecutionException e = assertThrows(ExecutionException.class, () -> saved.get(10, TimeUnit.SECONDS));

			assertInstanceOf(IllegalStateException.class, e.getCause());
			assertFalse(Files.exists(file));
		}
	}

	@Test
	void refusesFileThatExists() throws Exception {
		byte[] earlier = "earlier recording".getBytes(StandardCharsets.UTF_8);
		Path file = Files.write(tempDir.resolve("exists.mkv"), earlier);

		try (FakeTrack audio = FakeTrack.opus();
				ReplayBuffer buffer = buffer(Duration.ofSeconds(2))) {
			FakeFeed feed = new FakeFeed(buffer::sync, audio);

			audio.addTo(buffer);
			buffer.start();

			feed.play(millis(500));

			assertThrows(FileAlreadyExistsException.class, () -> buffer.save(file));
		}

		assertArrayEquals(earlier, Files.readAllBytes(file));
	}

	@Test
	void refusesSaveBeforeStart() throws Exception {
		try (FakeTrack audio = FakeTrack.opus();
				ReplayBuffer buffer = buffer(Duration.ofSeconds(2))) {
			audio.addTo(buffer);

			assertThrows(IllegalStateException.class, () -> buffer.save(tempDir.resolve("early.mkv")));
			assertFalse(Files.exists(tempDir.resolve("early.mkv")));
		}
	}

	@Test
	void refusesBadLimits() {
		assertThrows(IllegalArgumentException.class, () -> new ReplayBuffer(Duration.ofMillis(500)));
		assertThrows(IllegalArgumentException.class, () -> new ReplayBuffer(Duration.ofSeconds(5), 0));
	}

	/**
	 * Keeps a real call, to show that WebRTC's frames and key frames make a
	 * replay as the fake ones do. How fast the call runs is up to the
	 * machine, so only what holds however fast it runs is checked.
	 */
	@Test
	void savesCallReplay() throws Exception {
		SegmentedRecorderTest.Listener listener = new SegmentedRecorderTest.Listener();
		AudioDeviceModule audioModule = new AudioDeviceModule(AudioLayer.kDummyAudio);
		PeerConnectionFactory factory = new PeerConnectionFactory(audioModule);

		try (TestCall call = new TestCall(factory);
				ReplayBuffer buffer = new ReplayBuffer(Duration.ofSeconds(2))) {
			buffer.setListener(listener);
			buffer.addTrack(call.getVideoSender());
			buffer.addTrack(call.getAudioSender());
			buffer.start();

			assertTrue(listener.started.await(10, TimeUnit.SECONDS), "not started");

			Thread.sleep(3000);

			Path file = tempDir.resolve("call.mkv");
			MediaRecorderSegment replay = save(buffer, file);

			assertEquals(MediaRecorderResult.COMPLETE, replay.getResult());
			assertPlaysOnItsOwn(file, false);

			MediaInfo info = readInfo(file);

			assertEquals("vp8", info.getVideoCodec());
			assertEquals("opus", info.getAudioCodec());
		}
		finally {
			factory.dispose();
			audioModule.dispose();
		}

		assertNull(listener.error.get());
	}

	private static ReplayBuffer buffer(Duration window) throws IOException {
		return new ReplayBuffer(window, ReplayBuffer.DEFAULT_MAX_BYTES, FakeMedia.tableAddress());
	}

	private static MediaRecorderSegment save(ReplayBuffer buffer, Path file) throws Exception {
		return buffer.save(file).get(10, TimeUnit.SECONDS);
	}

}
