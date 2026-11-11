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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.RTCRtpReceiver;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;
import dev.onvoid.webrtc.media.player.MediaInfo;
import dev.onvoid.webrtc.media.player.MediaReader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Tests recording into segments.
 * <p>
 * Most tests feed the recorder {@link FakeTrack frames of their own} on a
 * {@link FakeFeed clock of their own}, so that where every segment begins
 * and ends follows from the frames alone, and is checked to the frame: video
 * at 25 frames a second, audio in frames of 20 ms, an encoder that answers a
 * key frame request with its next frame unless a test says otherwise. One
 * test records a real call, to show the same works with WebRTC's frames.
 */
@Execution(ExecutionMode.SAME_THREAD)
class SegmentedRecorderTest {

	@TempDir
	Path tempDir;


	@Test
	void splitsByDuration() throws Exception {
		SegmentOptions options = SegmentOptions.builder(tempDir.resolve("call-%05d.mkv"))
				.maxDuration(Duration.ofSeconds(2))
				.build();
		Listener listener = new Listener();

		try (FakeTrack video = FakeTrack.vp8();
				FakeTrack audio = FakeTrack.opus();
				MediaRecorder recorder = recorder(options)) {
			FakeFeed feed = new FakeFeed(recorder::sync, video, audio);

			recorder.setListener(listener);
			video.addTo(recorder);
			audio.addTo(recorder);
			recorder.start();

			feed.play(seconds(7));

			assertTrue(recorder.stop());
			assertEquals(MediaRecorderResult.COMPLETE, recorder.getResult());

			// Once a segment lasts two seconds, at the video frame at 2 s, the
			// encoder is asked for a key frame, and the next segment begins
			// with it, 40 ms later. The audio frame between goes into the
			// segment before, which ends 20 ms before the next begins.
			List<MediaRecorderSegment> segments = recorder.getSegments();

			assertSegments(segments, 0, 2020, 2040, 2020, 4080, 2020, 6120, 860);

			for (int i = 0; i < 3; i++) {
				assertStreams(segments.get(i).getFile(), video(51, 0, 2000), audio(102, 0, 2020));
			}
			assertStreams(segments.get(3).getFile(), video(22, 0, 840), audio(44, 0, 860));

			// One request for each segment that ended, none to spare.
			assertEquals(3, video.getKeyFrameRequests());

			recorder.sync();

			assertEquals(segments, listener.segments);
			assertEquals(segments.get(3).getFile(), recorder.getFile());
			assertNull(listener.error.get());
			assertEquals(Collections.emptyList(), listener.warnings);

			for (int i = 0; i < segments.size(); i++) {
				MediaRecorderSegment segment = segments.get(i);

				assertEquals(i, segment.getIndex());
				assertEquals(tempDir.resolve(String.format("call-%05d.mkv", i)), segment.getFile());
				assertEquals(MediaRecorderResult.COMPLETE, segment.getResult());
				assertEquals(Files.size(segment.getFile()), segment.getSize());
			}

			MediaInfo info = readInfo(segments.get(1).getFile());

			assertEquals("vp8", info.getVideoCodec());
			assertEquals("opus", info.getAudioCodec());

			// The recorder let go of the tracks.
			assertEquals(0, video.observers());
			assertEquals(0, audio.observers());
		}
	}

	@Test
	void splitsWebmByDuration() throws Exception {
		SegmentOptions options = SegmentOptions.builder(tempDir.resolve("call-%d.webm"))
				.maxDuration(Duration.ofSeconds(1))
				.build();

		try (FakeTrack video = FakeTrack.vp8();
				FakeTrack audio = FakeTrack.opus();
				MediaRecorder recorder = recorder(options)) {
			FakeFeed feed = new FakeFeed(recorder::sync, video, audio);

			video.addTo(recorder);
			audio.addTo(recorder);
			recorder.start();

			feed.play(millis(2500));

			assertTrue(recorder.stop());

			List<MediaRecorderSegment> segments = recorder.getSegments();

			assertSegments(segments, 0, 1020, 1040, 1020, 2080, 400);
			assertEquals(tempDir.resolve("call-2.webm"), segments.get(2).getFile());
			assertStreams(segments.get(0).getFile(), video(26, 0, 1000), audio(52, 0, 1020));
			assertEquals("vp8", readInfo(segments.get(0).getFile()).getVideoCodec());
		}
	}

	@Test
	void splitsBySize() throws Exception {
		long maxSize = 50_000;
		SegmentOptions options = SegmentOptions.builder(tempDir.resolve("size-%03d.mkv"))
				.maxSize(maxSize)
				.build();

		try (FakeTrack video = FakeTrack.vp8();
				FakeTrack audio = FakeTrack.opus();
				MediaRecorder recorder = recorder(options)) {
			FakeFeed feed = new FakeFeed(recorder::sync, video, audio);

			video.addTo(recorder);
			audio.addTo(recorder);
			recorder.start();

			feed.play(seconds(4));

			assertTrue(recorder.stop());

			// The header, a key frame of 4000 bytes, and 1200 bytes every 40 ms
			// after it: a segment holds 50 kB just before 1.56 s, and the next
			// one begins with the key frame then.
			List<MediaRecorderSegment> segments = recorder.getSegments();

			assertSegments(segments, 0, 1540, 1560, 1540, 3120, 860);

			for (int i = 0; i < 2; i++) {
				assertStreams(segments.get(i).getFile(), video(39, 0, 1520), audio(78, 0, 1540));
				assertTrue(segments.get(i).getSize() >= maxSize, segments.get(i).toString());
			}
			assertStreams(segments.get(2).getFile(), video(22, 0, 840), audio(44, 0, 860));
			assertEquals(2, video.getKeyFrameRequests());
		}
	}

	@Test
	void lateKeyFrameEndsSegment() throws Exception {
		SegmentOptions options = SegmentOptions.builder(tempDir.resolve("late-%d.mkv"))
				.maxDuration(Duration.ofSeconds(2))
				.build();

		try (FakeTrack video = FakeTrack.vp8().answerKeyFrameRequestsAfter(5);
				FakeTrack audio = FakeTrack.opus();
				MediaRecorder recorder = recorder(options)) {
			FakeFeed feed = new FakeFeed(recorder::sync, video, audio);

			video.addTo(recorder);
			audio.addTo(recorder);
			recorder.start();

			feed.play(seconds(3));

			assertTrue(recorder.stop());

			// Five more frames after the one at 2 s, then the key frame.
			List<MediaRecorderSegment> segments = recorder.getSegments();

			assertSegments(segments, 0, 2220, 2240, 740);
			assertStreams(segments.get(0).getFile(), video(56, 0, 2200), audio(112, 0, 2220));
			assertStreams(segments.get(1).getFile(), video(19, 0, 720), audio(38, 0, 740));
			assertEquals(1, video.getKeyFrameRequests());
		}
	}

	@Test
	void rotatesWithoutKeyFrameAfterWaiting() throws Exception {
		SegmentOptions options = SegmentOptions.builder(tempDir.resolve("wait-%d.mkv"))
				.maxDuration(Duration.ofSeconds(4))
				.build();

		try (FakeTrack video = FakeTrack.vp8().answerKeyFrameRequestsAfter(-1);
				FakeTrack audio = FakeTrack.opus();
				MediaRecorder recorder = recorder(options)) {
			FakeFeed feed = new FakeFeed(recorder::sync, video, audio);

			video.addTo(recorder);
			audio.addTo(recorder);
			recorder.start();

			// Due at 4 s, and no key frame in three seconds: the next segment
			// begins with the audio frame at 7.02 s. The video goes on in the
			// segment before for three seconds more, and is then dropped
			// until its next key frame, which comes at 10.52 s.
			feed.playUntil(millis(10500));
			video.sendKeyFrame();
			feed.playUntil(seconds(11));

			assertTrue(recorder.stop());

			List<MediaRecorderSegment> segments = recorder.getSegments();

			assertSegments(segments, 0, 10000, 7020, 3960);
			assertStreams(segments.get(0).getFile(), video(251, 0, 10000), audio(351, 0, 7000));
			assertStreams(segments.get(1).getFile(), video(12, 3500, 3940), audio(199, 0, 3960));

			// From 10.04 s to 10.48 s.
			assertEquals(12, recorder.getStats().getTracks().get(0).getDroppedAwaitingKeyFrame());
		}
	}

	@Test
	void drainsVideoTrackUntilItsKeyFrame() throws Exception {
		SegmentOptions options = SegmentOptions.builder(tempDir.resolve("both-%d.mkv"))
				.maxDuration(Duration.ofSeconds(2))
				.build();

		try (FakeTrack first = FakeTrack.vp8();
				FakeTrack second = FakeTrack.vp8().answerKeyFrameRequestsAfter(3);
				FakeTrack audio = FakeTrack.opus();
				MediaRecorder recorder = recorder(options)) {
			FakeFeed feed = new FakeFeed(recorder::sync, first, second, audio);

			// Half a frame later than the first video track.
			feed.startAt(second, millis(20));

			first.addTo(recorder);
			second.addTo(recorder);
			audio.addTo(recorder);
			recorder.start();

			feed.play(seconds(3));

			assertTrue(recorder.stop());

			// The next segment begins with the first track's key frame at
			// 2.04 s. The second track's key frame comes three frames after
			// the request, at 2.14 s, and until then its frames go into the
			// segment before, which then ends: neither has a gap.
			List<MediaRecorderSegment> segments = recorder.getSegments();

			assertSegments(segments, 0, 2100, 2040, 940);
			assertStreams(segments.get(0).getFile(),
					video(51, 0, 2000), video(53, 20, 2100), audio(102, 0, 2020));
			assertStreams(segments.get(1).getFile(),
					video(24, 0, 920), video(22, 100, 940), audio(48, 0, 940));

			assertEquals(75, second.getPushed());
			assertEquals(1, first.getKeyFrameRequests());
			assertEquals(1, second.getKeyFrameRequests());
		}
	}

	@Test
	void lastSegmentMayHoldOnlyItsKeyFrame() throws Exception {
		SegmentOptions options = SegmentOptions.builder(tempDir.resolve("stop-%d.mkv"))
				.maxDuration(Duration.ofSeconds(2))
				.build();

		try (FakeTrack audio = FakeTrack.opus();
				FakeTrack video = FakeTrack.vp8();
				MediaRecorder recorder = recorder(options)) {
			FakeFeed feed = new FakeFeed(recorder::sync, audio, video);

			audio.addTo(recorder);
			video.addTo(recorder);
			recorder.start();

			// Stopped right after the key frame at 2.04 s began a segment, and
			// before any audio went into it.
			feed.play(millis(2041));

			assertTrue(recorder.stop());

			List<MediaRecorderSegment> segments = recorder.getSegments();

			assertSegments(segments, 0, 2040, 2040, 0);
			assertStreams(segments.get(0).getFile(), audio(103, 0, 2040), video(51, 0, 2000));
			assertStreams(segments.get(1).getFile(), audio(0, 0, 0), video(1, 0, 0));
			assertEquals(MediaRecorderResult.COMPLETE, segments.get(1).getResult());
		}
	}

	@Test
	void keepsNewestSegments() throws Exception {
		SegmentOptions options = SegmentOptions.builder(tempDir.resolve("kept-%02d.mkv"))
				.maxDuration(Duration.ofSeconds(1))
				.maxSegments(2)
				.build();
		Listener listener = new Listener();

		try (FakeTrack video = FakeTrack.vp8();
				MediaRecorder recorder = recorder(options)) {
			FakeFeed feed = new FakeFeed(recorder::sync, video);

			recorder.setListener(listener);
			video.addTo(recorder);
			recorder.start();

			feed.play(seconds(5));

			assertTrue(recorder.stop());

			recorder.sync();

			// Every segment ends at the key frame after its second, however
			// close the requests come after one another.
			assertSegments(listener.segments, 0, 1000, 1040, 1000, 2080, 1000, 3120, 1000, 4160, 800);
			assertEquals(4, video.getKeyFrameRequests());

			List<MediaRecorderSegment> segments = recorder.getSegments();

			assertEquals(listener.segments.subList(3, 5), segments);
			assertEquals(List.of(tempDir.resolve("kept-03.mkv"), tempDir.resolve("kept-04.mkv")), filesIn(tempDir));
		}
	}

	@Test
	void keepsSegmentsWithinTotalSize() throws Exception {
		long maxTotalSize = 80_000;
		SegmentOptions options = SegmentOptions.builder(tempDir.resolve("total-%02d.mkv"))
				.maxDuration(Duration.ofSeconds(1))
				.maxTotalSize(maxTotalSize)
				.build();
		Listener listener = new Listener();

		try (FakeTrack video = FakeTrack.vp8();
				FakeTrack audio = FakeTrack.opus();
				MediaRecorder recorder = recorder(options)) {
			FakeFeed feed = new FakeFeed(recorder::sync, video, audio);

			recorder.setListener(listener);
			video.addTo(recorder);
			audio.addTo(recorder);
			recorder.start();

			feed.play(seconds(5));

			assertTrue(recorder.stop());

			recorder.sync();

			// The newest segments that fit together, about 35 kB each.
			List<MediaRecorderSegment> all = listener.segments;
			List<MediaRecorderSegment> expected = new ArrayList<>();
			long total = 0;

			assertEquals(5, all.size(), all.toString());

			for (int i = all.size() - 1; i >= 0; i--) {
				total += all.get(i).getSize();

				if (!expected.isEmpty() && total > maxTotalSize) {
					break;
				}
				expected.add(0, all.get(i));
			}

			assertEquals(2, expected.size(), all.toString());
			assertEquals(expected, recorder.getSegments());
			assertEquals(expected.stream().map(MediaRecorderSegment::getFile).collect(Collectors.toList()),
					filesIn(tempDir));
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

		try (FakeTrack video = FakeTrack.vp8();
				MediaRecorder recorder = recorder(options)) {
			FakeFeed feed = new FakeFeed(recorder::sync, video);

			video.addTo(recorder);
			recorder.start();

			feed.play(seconds(1));

			assertTrue(recorder.stop());

			List<MediaRecorderSegment> segments = recorder.getSegments();

			assertEquals(1, segments.size());
			assertEquals(8, segments.get(0).getIndex());
			assertEquals(dir.resolve("cam-008.mkv"), segments.get(0).getFile());
		}

		assertArrayEquals(earlier, Files.readAllBytes(existing));
	}

	@Test
	void changedCodecGoesOnInNextSegment() throws Exception {
		SegmentOptions options = SegmentOptions.builder(tempDir.resolve("codec-%02d.mkv"))
				.maxDuration(Duration.ofSeconds(60))
				.build();
		Listener listener = new Listener();

		try (FakeTrack video = FakeTrack.vp8();
				FakeTrack audio = FakeTrack.opus();
				MediaRecorder recorder = recorder(options)) {
			FakeFeed feed = new FakeFeed(recorder::sync, video, audio);

			recorder.setListener(listener);
			video.addTo(recorder);
			audio.addTo(recorder);
			recorder.start();

			feed.playUntil(seconds(2));
			video.switchCodec("video/VP9");
			feed.playUntil(seconds(4));

			assertTrue(recorder.stop());
			assertEquals(MediaRecorderResult.COMPLETE, recorder.getResult());

			// The next segment begins with the first frame in the new codec.
			List<MediaRecorderSegment> segments = recorder.getSegments();

			assertSegments(segments, 0, 1980, 2000, 1980);
			assertStreams(segments.get(0).getFile(), video(50, 0, 1960), audio(100, 0, 1980));
			assertStreams(segments.get(1).getFile(), video(50, 0, 1960), audio(100, 0, 1980));
			assertEquals("vp8", readInfo(segments.get(0).getFile()).getVideoCodec());
			assertEquals("vp9", readInfo(segments.get(1).getFile()).getVideoCodec());

			recorder.sync();

			assertEquals(List.of("Track 0 changed its codec from video/VP8 to video/VP9, and goes on in the next "
					+ "segment"), listener.warnings);
		}
	}

	@Test
	void lateTrackJoinsNextSegment() throws Exception {
		SegmentOptions options = SegmentOptions.builder(tempDir.resolve("join-%d.mkv"))
				.maxDuration(Duration.ofSeconds(2))
				.build();
		Listener listener = new Listener();

		try (FakeTrack video = FakeTrack.vp8();
				FakeTrack audio = FakeTrack.opus();
				MediaRecorder recorder = recorder(options)) {
			FakeFeed feed = new FakeFeed(recorder::sync, video, audio);

			feed.pause(audio);

			recorder.setListener(listener);
			video.addTo(recorder);
			audio.addTo(recorder);
			recorder.start();

			feed.playUntil(seconds(4));
			feed.resume(audio);
			feed.playUntil(seconds(6));

			assertTrue(recorder.stop());

			// The recording begins without the audio after waiting three
			// seconds for it, and asks for a key frame then, since the first
			// segment is over by then. The audio that begins at 4 s goes into
			// the segment after the one under way.
			List<MediaRecorderSegment> segments = recorder.getSegments();

			assertSegments(segments, 0, 2960, 3000, 2000, 5040, 940);
			assertStreams(segments.get(0).getFile(), video(75, 0, 2960));
			assertStreams(segments.get(1).getFile(), video(51, 0, 2000));
			assertStreams(segments.get(2).getFile(), video(24, 0, 920), audio(48, 0, 940));

			recorder.sync();

			assertEquals(List.of("Track 1 had no media to record when the recording began, and is left out until "
					+ "the next segment"), listener.warnings);
		}
	}

	@Test
	void failedSegmentEndsRecording() throws Exception {
		SegmentOptions options = SegmentOptions.builder(tempDir.resolve("failed-%02d.mkv"))
				.maxDuration(Duration.ofSeconds(1))
				.build();
		Listener listener = new Listener();

		// The header of the second segment.
		WriteFault.failHeader(1);

		try (FakeTrack video = FakeTrack.vp8();
				FakeTrack audio = FakeTrack.opus();
				MediaRecorder recorder = recorder(options)) {
			FakeFeed feed = new FakeFeed(recorder::sync, video, audio);

			recorder.setListener(listener);
			video.addTo(recorder);
			audio.addTo(recorder);
			recorder.start();

			feed.play(seconds(2));

			assertTrue(recorder.stop());
			assertEquals(MediaRecorderResult.PARTIAL, recorder.getResult());

			// The first segment is whole, the second deleted.
			List<MediaRecorderSegment> segments = recorder.getSegments();

			assertSegments(segments, 0, 1020);
			assertEquals(MediaRecorderResult.COMPLETE, segments.get(0).getResult());
			assertStreams(segments.get(0).getFile(), video(26, 0, 1000), audio(52, 0, 1020));

			recorder.sync();

			String error = listener.error.get();
			Path second = tempDir.resolve("failed-01.mkv");

			assertTrue(error.startsWith("Writing the header of " + second), error);
			assertFalse(Files.exists(second), second + " not deleted");
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

		try (FakeTrack video = FakeTrack.vp8();
				MediaRecorder recorder = recorder(options)) {
			FakeFeed feed = new FakeFeed(recorder::sync, video);

			recorder.setListener(listener);
			video.addTo(recorder);
			recorder.start();

			feed.play(seconds(3));

			assertTrue(recorder.stop());
			assertEquals(MediaRecorderResult.PARTIAL, recorder.getResult());

			List<MediaRecorderSegment> segments = recorder.getSegments();

			assertEquals(1, segments.size());
			assertEquals(MediaRecorderResult.PARTIAL, segments.get(0).getResult());
			assertTrue(Files.exists(segments.get(0).getFile()));

			recorder.sync();

			assertTrue(listener.error.get().startsWith("Writing to " + segments.get(0).getFile()),
					listener.error.get());
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

		try (FakeTrack video = FakeTrack.vp8();
				MediaRecorder recorder = recorder(options)) {
			FakeFeed feed = new FakeFeed(recorder::sync);

			video.addTo(recorder);
			recorder.start();

			feed.play(seconds(5));

			assertFalse(recorder.stop());
			assertEquals(MediaRecorderResult.EMPTY, recorder.getResult());
			assertTrue(recorder.getSegments().isEmpty());
			assertNull(recorder.getFile());
			assertEquals(0, video.observers());
		}

		assertTrue(filesIn(dir).isEmpty(), filesIn(dir).toString());
	}

	/**
	 * Records both sides of a real call, to show that WebRTC's own frames and
	 * key frames split as the fake ones do. How fast the call runs is up to
	 * the machine, so only what holds however fast it runs is checked.
	 */
	@Test
	void recordsCallInSegments() throws Exception {
		SegmentOptions options = SegmentOptions.builder(tempDir.resolve("call-%02d.mkv"))
				.maxDuration(Duration.ofSeconds(1))
				.build();
		Listener listener = new Listener();
		AudioDeviceModule audioModule = new AudioDeviceModule(AudioLayer.kDummyAudio);
		PeerConnectionFactory factory = new PeerConnectionFactory(audioModule);

		try (TestCall call = new TestCall(factory)) {
			RTCRtpReceiver video = call.getReceiver(0);

			try (MediaRecorder recorder = new MediaRecorder(options)) {
				recorder.setListener(listener);
				recorder.addTrack(call.getVideoSender());
				recorder.addTrack(video);
				recorder.addTrack(call.getAudioSender());
				recorder.start();

				assertTrue(listener.started.await(10, TimeUnit.SECONDS), "not started");

				Thread.sleep(3000);

				assertTrue(recorder.stop());
				assertEquals(MediaRecorderResult.COMPLETE, recorder.getResult());

				List<MediaRecorderSegment> segments = recorder.getSegments();

				assertFalse(segments.isEmpty());

				for (int i = 0; i < segments.size(); i++) {
					// Stopped at any moment, the last one may lack a stream.
					assertPlaysOnItsOwn(segments.get(i).getFile(), i == segments.size() - 1);
				}

				MediaInfo info = readInfo(segments.get(0).getFile());

				assertEquals("vp8", info.getVideoCodec());
				assertEquals("opus", info.getAudioCodec());
			}
			finally {
				video.dispose();
			}
		}
		finally {
			factory.dispose();
			audioModule.dispose();
		}

		assertNull(listener.error.get());
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

	private static MediaRecorder recorder(SegmentOptions options) throws IOException {
		return new MediaRecorder(options, FakeMedia.tableAddress());
	}

	static long seconds(long seconds) {
		return seconds * 1_000_000L;
	}

	static long millis(long millis) {
		return millis * 1_000L;
	}

	/**
	 * Checks where the segments begin in the recording and how long they
	 * last, in milliseconds: start, duration, start, duration, and so on.
	 */
	static void assertSegments(List<MediaRecorderSegment> segments, long... startsAndDurations) {
		List<String> actual = new ArrayList<>();
		List<String> expected = new ArrayList<>();

		for (MediaRecorderSegment segment : segments) {
			actual.add(segment.getStart().toMillis() + "+" + segment.getDuration().toMillis());
		}
		for (int i = 0; i + 1 < startsAndDurations.length; i += 2) {
			expected.add(startsAndDurations[i] + "+" + startsAndDurations[i + 1]);
		}

		assertEquals(expected, actual, segments.toString());
	}

	/** A video stream expected in a file: packets, first and last in ms. */
	static long[] video(long packets, long firstMs, long lastMs) {
		return new long[] { 0, packets, firstMs, lastMs };
	}

	/** An audio stream expected in a file: packets, first and last in ms. */
	static long[] audio(long packets, long firstMs, long lastMs) {
		return new long[] { 1, packets, firstMs, lastMs };
	}

	/**
	 * Checks the streams of a file, in the order of the tracks: their kind,
	 * how many packets they hold, and when the first and the last are, in
	 * milliseconds from the start of the file. A video stream with packets
	 * has to begin with a key frame, so that the file plays on its own.
	 */
	static void assertStreams(Path file, long[]... expected) throws IOException {
		List<RecordingProbe.Stream> streams = RecordingProbe.streamsOf(file);
		List<String> actual = new ArrayList<>();
		List<String> wanted = new ArrayList<>();

		for (RecordingProbe.Stream stream : streams) {
			String kind = stream.video ? "video" : "audio";

			actual.add(stream.packets == 0
					? kind + "[0]"
					: kind + "[" + stream.packets + ", " + stream.firstPtsUs / 1000 + "-" + stream.lastPtsUs / 1000
							+ (stream.video && !stream.startsWithKeyFrame ? ", no key frame first" : "") + "]");
		}
		for (long[] stream : expected) {
			String kind = stream[0] == 0 ? "video" : "audio";

			wanted.add(stream[1] == 0 ? kind + "[0]" : kind + "[" + stream[1] + ", " + stream[2] + "-" + stream[3] + "]");
		}

		assertEquals(wanted, actual, file.toString());
	}

	/**
	 * Checks that every stream of the file has packets, unless allowed not
	 * to, and that every video stream begins with a key frame near the
	 * start, as a segment that plays without the ones before must.
	 */
	static void assertPlaysOnItsOwn(Path file, boolean allowEmpty) throws IOException {
		List<RecordingProbe.Stream> list = RecordingProbe.streamsOf(file);

		assertFalse(list.isEmpty(), file.toString());

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


	static class Listener implements MediaRecorderListener {

		final CountDownLatch started = new CountDownLatch(1);

		final List<String> warnings = new CopyOnWriteArrayList<>();

		final AtomicReference<String> error = new AtomicReference<>();

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
		}

		@Override
		public void onSegmentFinished(MediaRecorderSegment segment) {
			segments.add(segment);
		}
	}

}
