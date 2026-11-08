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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.RTCRtpReceiver;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;
import dev.onvoid.webrtc.media.player.MediaInfo;
import dev.onvoid.webrtc.media.player.MediaReader;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Tests recording a call into segments, checked by reading each segment back
 * on its own.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class SegmentedRecorderTest {

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
	void splitsByDuration() throws Exception {
		SegmentOptions options = SegmentOptions.builder(tempDir.resolve("call-%05d.mkv"))
				.maxDuration(Duration.ofSeconds(2))
				.build();
		Listener listener = new Listener();

		try (TestCall call = new TestCall(factory);
				MediaRecorder recorder = new MediaRecorder(options)) {
			recorder.setListener(listener);
			recorder.addTrack(call.getVideoSender());
			recorder.addTrack(call.getAudioSender());
			recorder.start();

			assertTrue(listener.started.await(10, TimeUnit.SECONDS), "not started");
			assertNull(recorder.getFile());

			Thread.sleep(7000);

			assertTrue(recorder.stop());
			assertEquals(MediaRecorderResult.COMPLETE, recorder.getResult());

			List<MediaRecorderSegment> segments = recorder.getSegments();

			assertTrue(segments.size() >= 3, "segments: " + segments);
			assertEquals(segments.get(segments.size() - 1).getFile(), recorder.getFile());

			listener.awaitSegments(segments.size());

			assertEquals(segments, listener.segments);
			assertNull(listener.error.get());
			assertTrue(listener.warnings.isEmpty(), listener.warnings.toString());

			for (int i = 0; i < segments.size(); i++) {
				MediaRecorderSegment segment = segments.get(i);
				long durationMs = segment.getDuration().toMillis();

				assertEquals(i, segment.getIndex());
				assertEquals(tempDir.resolve(String.format("call-%05d.mkv", i)), segment.getFile());
				assertEquals(MediaRecorderResult.COMPLETE, segment.getResult());
				assertEquals(Files.size(segment.getFile()), segment.getSize());

				// Each ends at the key frame asked for once its time is up.
				if (i < segments.size() - 1) {
					assertTrue(durationMs >= 1900 && durationMs < 3500, segment.toString());

					MediaRecorderSegment next = segments.get(i + 1);
					long gapMs = next.getStart().minus(segment.getStart()).minus(segment.getDuration()).toMillis();

					// One follows the other, without a gap or an overlap.
					assertTrue(Math.abs(gapMs) < 200, "gap " + gapMs + " ms after " + segment);
				}

				assertPlaysOnItsOwn(segment.getFile(), 2);

				if (i > 0) {
					assertBeginsWithVideo(segment.getFile());
				}

				MediaInfo info = readInfo(segment.getFile());

				assertEquals("vp8", info.getVideoCodec());
				assertEquals("opus", info.getAudioCodec());
			}
		}
	}

	@Test
	void splitsWebmByDuration() throws Exception {
		SegmentOptions options = SegmentOptions.builder(tempDir.resolve("call-%d.webm"))
				.maxDuration(Duration.ofSeconds(1))
				.build();
		Listener listener = new Listener();

		try (TestCall call = new TestCall(factory);
				MediaRecorder recorder = new MediaRecorder(options)) {
			recorder.setListener(listener);
			recorder.addTrack(call.getVideoSender());
			recorder.addTrack(call.getAudioSender());
			recorder.start();

			assertTrue(listener.started.await(10, TimeUnit.SECONDS), "not started");

			Thread.sleep(3500);

			assertTrue(recorder.stop());

			List<MediaRecorderSegment> segments = recorder.getSegments();

			assertTrue(segments.size() >= 2, "segments: " + segments);

			for (MediaRecorderSegment segment : segments) {
				assertTrue(segment.getFile().getFileName().toString().matches("call-\\d\\.webm"), segment.toString());
				assertPlaysOnItsOwn(segment.getFile(), 2);
			}
		}
	}

	@Test
	void splitsBySize() throws Exception {
		long maxSize = 100_000;
		SegmentOptions options = SegmentOptions.builder(tempDir.resolve("size-%03d.mkv"))
				.maxSize(maxSize)
				.build();
		Listener listener = new Listener();

		try (TestCall call = new TestCall(factory);
				MediaRecorder recorder = new MediaRecorder(options)) {
			recorder.setListener(listener);
			recorder.addTrack(call.getVideoSender());
			recorder.addTrack(call.getAudioSender());
			recorder.start();

			assertTrue(listener.started.await(10, TimeUnit.SECONDS), "not started");

			Thread.sleep(6000);

			assertTrue(recorder.stop());

			List<MediaRecorderSegment> segments = recorder.getSegments();

			assertTrue(segments.size() >= 2, "segments: " + segments);

			for (int i = 0; i < segments.size() - 1; i++) {
				// Ends at the next key frame once the limit is reached.
				assertTrue(segments.get(i).getSize() >= maxSize, segments.get(i).toString());
			}
			for (MediaRecorderSegment segment : segments) {
				assertPlaysOnItsOwn(segment.getFile(), 2);
			}
		}
	}

	@Test
	void keepsNewestSegments() throws Exception {
		SegmentOptions options = SegmentOptions.builder(tempDir.resolve("kept-%02d.mkv"))
				.maxDuration(Duration.ofSeconds(1))
				.maxSegments(2)
				.build();
		Listener listener = new Listener();

		try (TestCall call = new TestCall(factory);
				MediaRecorder recorder = new MediaRecorder(options)) {
			recorder.setListener(listener);
			recorder.addTrack(call.getVideoSender());
			recorder.start();

			assertTrue(listener.started.await(10, TimeUnit.SECONDS), "not started");

			Thread.sleep(5000);

			assertTrue(recorder.stop());

			List<MediaRecorderSegment> segments = recorder.getSegments();

			assertEquals(2, segments.size(), segments.toString());

			long last = segments.get(1).getIndex();

			assertTrue(last >= 3, segments.toString());
			assertEquals(last - 1, segments.get(0).getIndex());

			// The older ones are deleted on the listener's thread.
			listener.awaitSegments((int) last + 1);

			List<Path> expected = segments.stream().map(MediaRecorderSegment::getFile).collect(Collectors.toList());

			awaitTrue(() -> filesIn(tempDir).equals(expected), () -> "files: " + filesIn(tempDir));
		}
	}

	@Test
	void keepsSegmentsWithinTotalSize() throws Exception {
		long maxTotalSize = 150_000;
		SegmentOptions options = SegmentOptions.builder(tempDir.resolve("total-%02d.mkv"))
				.maxDuration(Duration.ofSeconds(1))
				.maxTotalSize(maxTotalSize)
				.build();
		Listener listener = new Listener();

		try (TestCall call = new TestCall(factory);
				MediaRecorder recorder = new MediaRecorder(options)) {
			recorder.setListener(listener);
			recorder.addTrack(call.getVideoSender());
			recorder.addTrack(call.getAudioSender());
			recorder.start();

			assertTrue(listener.started.await(10, TimeUnit.SECONDS), "not started");

			Thread.sleep(6000);

			assertTrue(recorder.stop());

			List<MediaRecorderSegment> segments = recorder.getSegments();
			long total = segments.stream().mapToLong(MediaRecorderSegment::getSize).sum();

			assertTrue(segments.size() == 1 || total <= maxTotalSize, segments.toString());
			assertTrue(segments.get(0).getIndex() > 0, "nothing was deleted: " + segments);

			List<Path> expected = segments.stream().map(MediaRecorderSegment::getFile).collect(Collectors.toList());

			awaitTrue(() -> filesIn(tempDir).equals(expected), () -> "files: " + filesIn(tempDir));
		}
	}

	@Test
	void numbersOnAfterExistingSegments(@TempDir Path dir) throws Exception {
		byte[] earlier = "earlier recording".getBytes(StandardCharsets.UTF_8);
		Path existing = Files.write(dir.resolve("cam-007.mkv"), earlier);
		Files.write(dir.resolve("other-100.mkv"), earlier);

		SegmentOptions options = SegmentOptions.builder(dir.resolve("cam-%03d.mkv"))
				.maxDuration(Duration.ofSeconds(60))
				.build();
		Listener listener = new Listener();

		try (TestCall call = new TestCall(factory);
				MediaRecorder recorder = new MediaRecorder(options)) {
			recorder.setListener(listener);
			recorder.addTrack(call.getVideoSender());
			recorder.start();

			assertTrue(listener.started.await(10, TimeUnit.SECONDS), "not started");

			Thread.sleep(1000);

			assertTrue(recorder.stop());

			List<MediaRecorderSegment> segments = recorder.getSegments();

			assertEquals(1, segments.size());
			assertEquals(8, segments.get(0).getIndex());
			assertEquals(dir.resolve("cam-008.mkv"), segments.get(0).getFile());
		}

		assertArrayEquals(earlier, Files.readAllBytes(existing));
	}

	@Test
	void recordsBothSidesInEverySegment() throws Exception {
		SegmentOptions options = SegmentOptions.builder(tempDir.resolve("both-%02d.mkv"))
				.maxDuration(Duration.ofSeconds(2))
				.build();
		Listener listener = new Listener();

		try (TestCall call = new TestCall(factory)) {
			RTCRtpReceiver video = call.getReceiver(0);

			try (MediaRecorder recorder = new MediaRecorder(options)) {
				recorder.setListener(listener);
				recorder.addTrack(call.getVideoSender());
				recorder.addTrack(video);
				recorder.addTrack(call.getAudioSender());
				recorder.start();

				assertTrue(listener.started.await(10, TimeUnit.SECONDS), "not started");

				Thread.sleep(6000);

				assertTrue(recorder.stop());

				List<MediaRecorderSegment> segments = recorder.getSegments();

				assertTrue(segments.size() >= 2, "segments: " + segments);

				for (int i = 0; i < segments.size(); i++) {
					// Both video tracks begin at a key frame of their own. In the
					// last segment, one may not have reached it before the stop,
					// its frames having gone into the segment before.
					assertPlaysOnItsOwn(segments.get(i).getFile(), 3, i == segments.size() - 1);
				}
			}
			finally {
				video.dispose();
			}
		}

		assertNull(listener.error.get());
	}

	@Test
	void changedCodecGoesOnInNextSegment() throws Exception {
		SegmentOptions options = SegmentOptions.builder(tempDir.resolve("codec-%02d.mkv"))
				.maxDuration(Duration.ofSeconds(60))
				.build();
		Listener listener = new Listener();

		try (TestCall call = new TestCall(factory);
				MediaRecorder recorder = new MediaRecorder(options)) {
			recorder.setListener(listener);
			recorder.addTrack(call.getVideoSender());
			recorder.addTrack(call.getAudioSender());
			recorder.start();

			assertTrue(listener.started.await(10, TimeUnit.SECONDS), "not started");

			Thread.sleep(2000);

			call.switchVideoCodec("VP9");

			Thread.sleep(3000);

			assertTrue(recorder.stop());
			assertEquals(MediaRecorderResult.COMPLETE, recorder.getResult());

			List<MediaRecorderSegment> segments = recorder.getSegments();

			assertEquals(2, segments.size(), segments.toString());
			assertEquals("vp8", readInfo(segments.get(0).getFile()).getVideoCodec());
			assertEquals("vp9", readInfo(segments.get(1).getFile()).getVideoCodec());

			for (MediaRecorderSegment segment : segments) {
				assertPlaysOnItsOwn(segment.getFile(), 2);
			}
		}

		assertTrue(listener.warnings.stream().anyMatch(warning -> warning.contains("changed its codec")),
				listener.warnings.toString());
	}

	@Test
	void failedSegmentEndsRecording() throws Exception {
		SegmentOptions options = SegmentOptions.builder(tempDir.resolve("failed-%02d.mkv"))
				.maxDuration(Duration.ofSeconds(1))
				.build();
		Listener listener = new Listener();

		// The header of the second segment.
		WriteFault.failHeader(1);

		try (TestCall call = new TestCall(factory);
				MediaRecorder recorder = new MediaRecorder(options)) {
			recorder.setListener(listener);
			recorder.addTrack(call.getVideoSender());
			recorder.addTrack(call.getAudioSender());
			recorder.start();

			assertTrue(listener.started.await(10, TimeUnit.SECONDS), "not started");
			assertTrue(listener.failed.await(10, TimeUnit.SECONDS), "no error");

			assertTrue(recorder.stop());
			assertEquals(MediaRecorderResult.PARTIAL, recorder.getResult());

			List<MediaRecorderSegment> segments = recorder.getSegments();

			// The first segment is whole, the second deleted.
			assertEquals(1, segments.size(), segments.toString());
			assertEquals(MediaRecorderResult.COMPLETE, segments.get(0).getResult());
			assertPlaysOnItsOwn(segments.get(0).getFile(), 2);

			String error = listener.error.get();
			Path second = tempDir.resolve("failed-01.mkv");

			assertTrue(error.startsWith("Writing the header of " + second), error);

			awaitTrue(() -> !Files.exists(second), () -> second + " not deleted");
		}
		finally {
			WriteFault.disarm();
		}
	}

	@Test
	void failedWriteKeepsPartialSegment() throws Exception {
		SegmentOptions options = SegmentOptions.builder(tempDir.resolve("partial-%02d.mkv"))
				.maxDuration(Duration.ofSeconds(60))
				.build();
		Listener listener = new Listener();

		WriteFault.failPacket(50);

		try (TestCall call = new TestCall(factory);
				MediaRecorder recorder = new MediaRecorder(options)) {
			recorder.setListener(listener);
			recorder.addTrack(call.getVideoSender());
			recorder.start();

			assertTrue(listener.failed.await(10, TimeUnit.SECONDS), "no error");

			assertTrue(recorder.stop());
			assertEquals(MediaRecorderResult.PARTIAL, recorder.getResult());

			List<MediaRecorderSegment> segments = recorder.getSegments();

			assertEquals(1, segments.size());
			assertEquals(MediaRecorderResult.PARTIAL, segments.get(0).getResult());
			assertTrue(Files.exists(segments.get(0).getFile()));
		}
		finally {
			WriteFault.disarm();
		}
	}

	@Test
	void recordingWithoutMediaLeavesNoSegment(@TempDir Path dir) throws Exception {
		SegmentOptions options = SegmentOptions.builder(dir.resolve("empty-%d.mkv"))
				.maxDuration(Duration.ofSeconds(1))
				.build();

		try (TestCall call = new TestCall(factory);
				MediaRecorder recorder = new MediaRecorder(options)) {
			recorder.addTrack(call.getVideoSender());
			recorder.start();

			assertFalse(recorder.stop());
			assertEquals(MediaRecorderResult.EMPTY, recorder.getResult());
			assertTrue(recorder.getSegments().isEmpty());
			assertNull(recorder.getFile());
		}

		assertTrue(filesIn(dir).isEmpty(), filesIn(dir).toString());
	}

	@Test
	void refusesMissingDirectory() {
		SegmentOptions options = SegmentOptions.builder(tempDir.resolve("missing").resolve("%d.mkv"))
				.maxDuration(Duration.ofSeconds(1))
				.build();

		assertThrows(NoSuchFileException.class, () -> new MediaRecorder(options));
	}

	@Test
	void parsesPattern() {
		SegmentOptions options = SegmentOptions.builder(tempDir.resolve("100%%-%04d.mkv"))
				.maxSize(1)
				.build();

		assertEquals(4, options.width());
		assertEquals(".mkv", options.nameSuffix());
		assertEquals(tempDir.toAbsolutePath() + tempDir.getFileSystem().getSeparator() + "100%-",
				options.pathPrefix());
		assertEquals(12, options.indexOf("100%-0012.mkv"));
		assertEquals(12345, options.indexOf("100%-12345.mkv"));
		assertEquals(-1, options.indexOf("100%-.mkv"));
		assertEquals(-1, options.indexOf("100%-12a.mkv"));
		assertEquals(-1, options.indexOf("100%-12.webm"));

		SegmentOptions plain = SegmentOptions.builder(tempDir.resolve("%d")).maxSize(1).build();

		assertEquals(1, plain.width());
		assertEquals("", plain.nameSuffix());
		assertEquals(3, plain.indexOf("3"));
	}

	@Test
	void refusesBadPatterns() {
		for (String name : new String[] { "call.mkv", "%d-%d.mkv", "%5d.mkv", "%s.mkv", "50%.mkv", "%019d.mkv",
				"%0d.mkv" }) {
			assertThrows(IllegalArgumentException.class, () -> SegmentOptions.builder(tempDir.resolve(name)),
					name);
		}
	}

	@Test
	void refusesBadLimits() {
		SegmentOptions.Builder builder = SegmentOptions.builder(tempDir.resolve("%d.mkv"));

		assertThrows(IllegalStateException.class, builder::build);
		assertThrows(IllegalArgumentException.class, () -> builder.maxDuration(Duration.ofMillis(999)));
		assertThrows(IllegalArgumentException.class, () -> builder.maxSize(0));
		assertThrows(IllegalArgumentException.class, () -> builder.maxSegments(0));
		assertThrows(IllegalArgumentException.class, () -> builder.maxTotalSize(-1));
	}

	/**
	 * Checks that the file has the given number of streams, each of which
	 * begins at a key frame near the start of the file, as a segment that
	 * plays without the ones before must.
	 */
	static List<RecordingProbe.Stream> assertPlaysOnItsOwn(Path file, int streams) throws IOException {
		return assertPlaysOnItsOwn(file, streams, false);
	}

	/**
	 * As {@link #assertPlaysOnItsOwn(Path, int)}, allowing streams without
	 * packets if asked to.
	 */
	static List<RecordingProbe.Stream> assertPlaysOnItsOwn(Path file, int streams, boolean allowEmpty)
			throws IOException {
		List<RecordingProbe.Stream> list = RecordingProbe.streamsOf(file);

		assertEquals(streams, list.size(), file + ": " + list);

		for (RecordingProbe.Stream stream : list) {
			if (allowEmpty && stream.packets == 0) {
				continue;
			}

			assertTrue(stream.packets > 0, file + ": " + list);

			if (stream.video) {
				assertTrue(stream.startsWithKeyFrame, file + ": " + list);
			}

			// Every stream begins near zero, so the tracks stay in sync.
			assertTrue(stream.firstPtsUs >= 0 && stream.firstPtsUs < 1_000_000, file + ": " + list);
		}

		return list;
	}

	/**
	 * Checks that a segment after the first begins with the key frame of a
	 * video track, which ended the segment before.
	 */
	static void assertBeginsWithVideo(Path file) throws IOException {
		List<RecordingProbe.Stream> list = RecordingProbe.streamsOf(file);

		assertTrue(list.stream().anyMatch(stream -> stream.video && stream.firstPtsUs < 50_000),
				file + ": " + list);
	}

	static MediaInfo readInfo(Path file) throws Exception {
		try (MediaReader reader = new MediaReader(file)) {
			return reader.getInfo();
		}
	}

	static List<Path> filesIn(Path dir) {
		try (Stream<Path> files = Files.list(dir)) {
			return files.sorted().collect(Collectors.toList());
		}
		catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	static void awaitTrue(BooleanSupplier condition, java.util.function.Supplier<String> message)
			throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

		while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
			Thread.sleep(20);
		}

		assertTrue(condition.getAsBoolean(), message);
	}


	static class Listener implements MediaRecorderListener {

		final CountDownLatch started = new CountDownLatch(1);

		final List<String> warnings = new CopyOnWriteArrayList<>();

		final AtomicReference<String> error = new AtomicReference<>();

		final CountDownLatch failed = new CountDownLatch(1);

		final List<MediaRecorderSegment> segments = new CopyOnWriteArrayList<>();


		@Override
		public void onStarted() {
			started.countDown();
		}

		@Override
		public void onWarning(String message) {
			warnings.add(message);
		}

		@Override
		public void onError(String message) {
			error.compareAndSet(null, message);
			failed.countDown();
		}

		@Override
		public void onSegmentFinished(MediaRecorderSegment segment) {
			segments.add(segment);
		}

		void awaitSegments(int count) throws InterruptedException {
			awaitTrue(() -> segments.size() >= count, () -> "segments: " + segments);
		}
	}

}
