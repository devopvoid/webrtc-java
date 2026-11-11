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

import static dev.onvoid.webrtc.media.recorder.SegmentedRecorderTest.assertStreams;
import static dev.onvoid.webrtc.media.recorder.SegmentedRecorderTest.audio;
import static dev.onvoid.webrtc.media.recorder.SegmentedRecorderTest.millis;
import static dev.onvoid.webrtc.media.recorder.SegmentedRecorderTest.seconds;
import static dev.onvoid.webrtc.media.recorder.SegmentedRecorderTest.video;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
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
import dev.onvoid.webrtc.RTCRtpSender;
import dev.onvoid.webrtc.e2ee.KeyProvider;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;
import dev.onvoid.webrtc.media.player.AllocationFault;
import dev.onvoid.webrtc.media.player.MediaInfo;
import dev.onvoid.webrtc.media.player.MediaReader;
import dev.onvoid.webrtc.media.player.UnicodeFileName;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Tests recording into a single file, checked by reading the file back.
 * <p>
 * Most tests feed the recorder {@link FakeTrack frames of their own} on a
 * {@link FakeFeed clock of their own}, as in {@link SegmentedRecorderTest},
 * so that what goes into the file, what is dropped and when a fault strikes
 * follow from the frames alone. A few record a call between two local peer
 * connections, for what only WebRTC's own tracks can show: a receiver asked
 * for a key frame, and end-to-end encryption. How fast the call runs is up to
 * the machine, so they check only what holds however fast it runs.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class MediaRecorderTest {

	private static final long RECORD_MS = 3000;

	/** What a file holds that a recording must not cost. */
	private static final byte[] EARLIER_RECORDING = "earlier recording".getBytes(StandardCharsets.UTF_8);

	private AudioDeviceModule audioModule;
	private PeerConnectionFactory factory;

	@TempDir
	Path tempDir;


	@BeforeAll
	void initFactory() {
		// A dummy audio layer, because this factory sends pushed audio.
		audioModule = new AudioDeviceModule(AudioLayer.kDummyAudio);
		factory = new PeerConnectionFactory(audioModule);
	}

	@AfterAll
	void disposeFactory() {
		factory.dispose();
		audioModule.dispose();
	}

	@Test
	void recordsIntoMatroska() throws Exception {
		Path file = tempDir.resolve("tracks.mkv");
		Listener listener = new Listener();

		try (FakeTrack video = FakeTrack.vp8();
				FakeTrack audio = FakeTrack.opus();
				MediaRecorder recorder = recorder(file)) {
			FakeFeed feed = new FakeFeed(recorder::sync, video, audio);

			recorder.setListener(listener);
			video.addTo(recorder);
			audio.addTo(recorder);
			recorder.start();

			assertEquals(MediaRecorderState.RECORDING, recorder.getState());

			feed.play(seconds(3));

			assertEquals(0, listener.started.getCount(), "not started");
			assertNull(recorder.getResult());

			assertTrue(recorder.stop());
			assertEquals(MediaRecorderState.STOPPED, recorder.getState());
			assertEquals(MediaRecorderResult.COMPLETE, recorder.getResult());
			assertEquals(file.toAbsolutePath(), recorder.getFile());
			assertEquals(0, video.observers());
			assertEquals(0, audio.observers());

			recorder.sync();
		}

		assertNull(listener.error.get());
		assertEquals(Collections.emptyList(), listener.warnings);

		assertStreams(file, video(75, 0, 2960), audio(150, 0, 2980));

		MediaInfo info = readInfo(file);

		assertEquals("vp8", info.getVideoCodec());
		assertEquals(FakeTrack.WIDTH, info.getVideoWidth());
		assertEquals(FakeTrack.HEIGHT, info.getVideoHeight());
		assertEquals("opus", info.getAudioCodec());
		assertEquals(48000, info.getSampleRate());
	}

	@Test
	void recordsIntoWebm() throws Exception {
		Path file = tempDir.resolve("tracks.webm");

		try (FakeTrack video = FakeTrack.vp8();
				FakeTrack audio = FakeTrack.opus();
				MediaRecorder recorder = recorder(file)) {
			FakeFeed feed = new FakeFeed(recorder::sync, video, audio);

			video.addTo(recorder);
			audio.addTo(recorder);
			recorder.start();

			feed.play(seconds(1));

			assertTrue(recorder.stop());
		}

		assertStreams(file, video(25, 0, 960), audio(50, 0, 980));

		MediaInfo info = readInfo(file);

		assertEquals("vp8", info.getVideoCodec());
		assertEquals("opus", info.getAudioCodec());
	}

	@Test
	void leavesOutTrackWithoutMediaAtFirst() throws Exception {
		Path file = tempDir.resolve("late.mkv");
		Listener listener = new Listener();

		try (FakeTrack video = FakeTrack.vp8();
				FakeTrack audio = FakeTrack.opus();
				MediaRecorder recorder = recorder(file)) {
			FakeFeed feed = new FakeFeed(recorder::sync, video, audio);

			feed.pause(audio);

			recorder.setListener(listener);
			video.addTo(recorder);
			audio.addTo(recorder);
			recorder.start();

			// The file waits three seconds for the audio, then begins without
			// it, from the first video frame; audio that comes later has no
			// stream to go into.
			feed.playUntil(seconds(4));
			feed.resume(audio);
			feed.playUntil(seconds(5));

			assertTrue(recorder.stop());

			recorder.sync();
		}

		assertStreams(file, video(125, 0, 4960));
		assertEquals(List.of("Track 1 had no media to record when the recording began, and is left out"),
				listener.warnings);
	}

	@Test
	void leavesOutTrackThatChangesItsCodec() throws Exception {
		Path file = tempDir.resolve("changed.mkv");
		Listener listener = new Listener();

		try (FakeTrack video = FakeTrack.vp8();
				FakeTrack audio = FakeTrack.opus();
				MediaRecorder recorder = recorder(file)) {
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

			recorder.sync();
		}

		// A stream in a file keeps its codec: the video ends at the change,
		// and the audio goes on.
		assertStreams(file, video(50, 0, 1960), audio(200, 0, 3980));
		assertEquals(List.of("Track 0 changed its codec and is no longer recorded"), listener.warnings);
	}

	@Test
	void recordsReceivedCallIntoMatroska() throws Exception {
		Path file = tempDir.resolve("received.mkv");
		Listener listener = new Listener();

		try (TestCall call = new TestCall(factory)) {
			// Well into the call, so that the receiver has had its key frame
			// long ago and the recorder has to ask the sender for one.
			Thread.sleep(1500);

			RTCRtpReceiver video = call.getReceiver(0);
			RTCRtpReceiver audio = call.getReceiver(1);

			try (MediaRecorder recorder = new MediaRecorder(file)) {
				recorder.setListener(listener);
				recorder.addTrack(video);
				recorder.addTrack(audio);
				recorder.start();

				assertTrue(listener.started.await(10, TimeUnit.SECONDS), "not started");

				Thread.sleep(RECORD_MS);

				assertTrue(recorder.stop());
				assertEquals(MediaRecorderResult.COMPLETE, recorder.getResult());
			}
			finally {
				video.dispose();
				audio.dispose();
			}
		}

		assertNull(listener.error.get());
		assertTrue(listener.warnings.isEmpty(), listener.warnings.toString());

		MediaInfo info = readInfo(file);

		assertEquals("vp8", info.getVideoCodec());
		assertEquals(TestCall.WIDTH, info.getVideoWidth());
		assertEquals(TestCall.HEIGHT, info.getVideoHeight());
		assertEquals("opus", info.getAudioCodec());
		assertEquals(48000, info.getSampleRate());
		assertTrue(info.getDurationUs() > 0, "duration: " + info.getDurationUs());
	}

	@Test
	void recordsSentCallIntoWebm() throws Exception {
		Path file = tempDir.resolve("sent.webm");
		Listener listener = new Listener();

		try (TestCall call = new TestCall(factory);
				MediaRecorder recorder = new MediaRecorder(file)) {
			recorder.setListener(listener);
			recorder.addTrack(call.getVideoSender());
			recorder.addTrack(call.getAudioSender());
			recorder.start();

			assertTrue(listener.started.await(10, TimeUnit.SECONDS), "not started");

			Thread.sleep(RECORD_MS);

			assertTrue(recorder.stop());
		}

		assertNull(listener.error.get());

		MediaInfo info = readInfo(file);

		assertEquals("vp8", info.getVideoCodec());
		assertEquals("opus", info.getAudioCodec());
		assertTrue(info.getDurationUs() > 0, "duration: " + info.getDurationUs());
	}

	@Test
	void refusesEncryptedTracks() throws Exception {
		KeyProvider callerKeys = KeyProvider.builder(1).build();
		KeyProvider calleeKeys = KeyProvider.builder(2).build();
		byte[] key = callerKeys.generateKey();
		callerKeys.setEncryptionKey(0, key);
		calleeKeys.setDecryptionKey(1, 0, key);

		try (TestCall call = new TestCall(factory);
				MediaRecorder recorder = new MediaRecorder(tempDir.resolve("refused.mkv"))) {
			RTCRtpSender sender = call.getVideoSender();
			RTCRtpReceiver receiver = call.getReceiver(0);

			sender.setTransform(callerKeys.createEncryptor());
			receiver.setTransform(calleeKeys.createDecryptor());

			// The decrypted media is not to be stored, unless the keys say so.
			assertThrows(IllegalStateException.class, () -> recorder.addTrack(sender));
			assertThrows(IllegalStateException.class, () -> recorder.addTrack(receiver));

			// A refused track leaves the recorder as it was.
			recorder.addTrack(call.getAudioSender());
			assertEquals(MediaRecorderState.IDLE, recorder.getState());

			receiver.dispose();
		}
	}

	@Test
	void recordsAllowedEncryptedCall() throws Exception {
		Path file = tempDir.resolve("encrypted.mkv");
		Listener listener = new Listener();

		KeyProvider callerKeys = KeyProvider.builder(1).build();
		KeyProvider calleeKeys = KeyProvider.builder(2).allowPlaintextRecording(true).build();
		byte[] key = callerKeys.generateKey();
		callerKeys.setEncryptionKey(0, key);
		calleeKeys.setDecryptionKey(1, 0, key);

		try (TestCall call = new TestCall(factory)) {
			RTCRtpReceiver video = call.getReceiver(0);
			RTCRtpReceiver audio = call.getReceiver(1);

			// The receivers decrypt before the senders encrypt, so that no
			// frame reaches them in the clear.
			video.setTransform(calleeKeys.createDecryptor(video::requestKeyFrame));
			audio.setTransform(calleeKeys.createDecryptor());
			call.getVideoSender().setTransform(callerKeys.createEncryptor());
			call.getAudioSender().setTransform(callerKeys.createEncryptor());

			try (MediaRecorder recorder = new MediaRecorder(file)) {
				recorder.setListener(listener);
				recorder.addTrack(video);
				recorder.addTrack(audio);
				recorder.start();

				assertTrue(listener.started.await(10, TimeUnit.SECONDS), "not started");

				Thread.sleep(RECORD_MS);

				assertTrue(recorder.stop());
			}
			finally {
				video.dispose();
				audio.dispose();
			}
		}

		assertNull(listener.error.get());
		assertTrue(calleeKeys.getStats().getDecryptedFrames() > 0);

		// What was recorded is the decrypted media, which reads as such.
		MediaInfo info = readInfo(file);

		assertEquals("vp8", info.getVideoCodec());
		assertEquals("opus", info.getAudioCodec());
		assertTrue(info.getDurationUs() > 0, "duration: " + info.getDurationUs());
	}

	@Test
	void recordsIntoPathBeyondBasicPlane() throws Exception {
		// FFmpeg takes UTF-8, which is not the modified UTF-8 of JNI for
		// characters beyond the Basic Multilingual Plane. A directory of its
		// own shows that no file of another name was written.
		Path dir = Files.createDirectory(tempDir.resolve("unicode"));
		Path file = UnicodeFileName.resolve(dir, "mkv");

		recordVideo(file, false);

		try (Stream<Path> files = Files.list(dir)) {
			List<Path> written = files.collect(Collectors.toList());

			// Compared as files, not as paths: on macOS Java holds the name
			// decomposed (ü as u and a diaeresis), while FFmpeg writes it as
			// given, and the file system finds the file by either form.
			assertEquals(1, written.size(), () -> "Written: " + written);
			assertTrue(Files.isSameFile(file, written.get(0)), () -> "Written: " + written);
		}

		assertEquals("vp8", readInfo(file).getVideoCodec());
	}

	@Test
	void failedOpenBeyondBasicPlaneNamesPath() {
		Path file = UnicodeFileName.resolve(tempDir.resolve("no-such-dir"), "mkv");

		IOException e = assertThrows(IOException.class, () -> new MediaRecorder(file));

		assertTrue(e.getMessage().contains(UnicodeFileName.NAME), e.getMessage());
	}

	@Test
	void failedNativeOpenNamesPath(@TempDir Path dir) {
		Path file = UnicodeFileName.resolve(dir, "mkv");

		WriteFault.failOpen();

		try {
			IOException e = assertThrows(IOException.class, () -> new MediaRecorder(file));

			// The path comes back to Java as it went in.
			assertTrue(e.getMessage().contains(UnicodeFileName.NAME), e.getMessage());
		}
		finally {
			WriteFault.disarm();
		}

		assertEquals(Collections.emptyList(), filesIn(dir));
	}

	@Test
	void refusesFileThatExists() throws Exception {
		Path file = tempDir.resolve("refused.mkv");

		Files.write(file, EARLIER_RECORDING);

		assertThrows(FileAlreadyExistsException.class, () -> new MediaRecorder(file));
		assertArrayEquals(EARLIER_RECORDING, Files.readAllBytes(file));
	}

	@Test
	void replacesFileOnceFinished(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("replaced.mkv");
		Listener listener = new Listener();

		Files.write(file, EARLIER_RECORDING);

		try (FakeTrack video = FakeTrack.vp8();
				MediaRecorder recorder = recorder(file, true)) {
			FakeFeed feed = new FakeFeed(recorder::sync, video);

			recorder.setListener(listener);
			video.addTo(recorder);
			recorder.start();

			feed.play(seconds(1));

			assertEquals(0, listener.started.getCount(), "not started");
			assertArrayEquals(EARLIER_RECORDING, Files.readAllBytes(file));
			assertEquals(2, filesIn(dir).size());

			assertTrue(recorder.stop());
			assertEquals(MediaRecorderResult.COMPLETE, recorder.getResult());
			assertEquals(file, recorder.getFile());

			recorder.sync();
		}

		assertNull(listener.error.get());
		assertEquals(Collections.singletonList(file), filesIn(dir));
		assertStreams(file, video(25, 0, 960));
	}

	@Test
	void replacingCreatesMissingFile(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("created.mp4");

		try (FakeTrack audio = FakeTrack.opus();
				MediaRecorder recorder = recorder(file, true)) {
			FakeFeed feed = new FakeFeed(recorder::sync, audio);

			audio.addTo(recorder);
			recorder.start();

			feed.play(seconds(1));

			assertTrue(recorder.stop());
		}

		assertEquals(Collections.singletonList(file), filesIn(dir));

		// The temporary file kept the extension, which picks the container.
		byte[] start = Arrays.copyOf(Files.readAllBytes(file), 8);

		assertEquals("ftyp", new String(start, 4, 4, StandardCharsets.US_ASCII));
	}

	@Test
	void recordingWithoutMediaKeepsReplacedFile(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("kept.mkv");

		Files.write(file, EARLIER_RECORDING);

		try (FakeTrack video = FakeTrack.vp8();
				MediaRecorder recorder = recorder(file, true)) {
			FakeFeed feed = new FakeFeed(recorder::sync);

			video.addTo(recorder);
			recorder.start();

			feed.play(seconds(1));

			assertFalse(recorder.stop());
			assertEquals(MediaRecorderResult.EMPTY, recorder.getResult());
		}

		assertArrayEquals(EARLIER_RECORDING, Files.readAllBytes(file));
		assertEquals(Collections.singletonList(file), filesIn(dir));
	}

	@Test
	void unstartedRecorderKeepsReplacedFile(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("kept.mkv");

		Files.write(file, EARLIER_RECORDING);

		new MediaRecorder(file, true).close();

		assertArrayEquals(EARLIER_RECORDING, Files.readAllBytes(file));
		assertEquals(Collections.singletonList(file), filesIn(dir));
	}

	@Test
	void failedRecordingKeepsReplacedFile(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("kept.mkv");
		Listener listener = new Listener();

		Files.write(file, EARLIER_RECORDING);

		WriteFault.failHeader();

		try (FakeTrack video = FakeTrack.vp8();
				MediaRecorder recorder = recorder(file, true)) {
			FakeFeed feed = new FakeFeed(recorder::sync, video);

			recorder.setListener(listener);
			video.addTo(recorder);
			recorder.start();

			feed.play(seconds(1));

			assertFalse(recorder.stop());
			assertEquals(MediaRecorderResult.FAILED, recorder.getResult());

			recorder.sync();
		}
		finally {
			WriteFault.disarm();
		}

		assertErrorStartsWith(listener, "Writing the header of ");
		assertArrayEquals(EARLIER_RECORDING, Files.readAllBytes(file));
		assertEquals(Collections.singletonList(file), filesIn(dir));
	}

	@Test
	void failedReplaceKeepsRecording(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("blocked.mkv");
		Listener listener = new Listener();

		try (FakeTrack video = FakeTrack.vp8();
				MediaRecorder recorder = recorder(file, true)) {
			FakeFeed feed = new FakeFeed(recorder::sync, video);

			recorder.setListener(listener);
			video.addTo(recorder);
			recorder.start();

			feed.play(seconds(1));

			// No file can take the place of a directory that holds something.
			Files.createFile(Files.createDirectory(file).resolve("child"));

			assertTrue(recorder.stop());
			assertEquals(MediaRecorderResult.PARTIAL, recorder.getResult());
			assertFalse(file.equals(recorder.getFile()));
			assertStreams(recorder.getFile(), video(25, 0, 960));

			recorder.sync();
		}

		assertErrorStartsWith(listener, "Replacing " + file + " failed");
	}

	@Test
	void replacingKeepsPermissions(@TempDir Path dir) throws Exception {
		Assumptions.assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));

		Path file = dir.resolve("private.mkv");

		Files.write(file, EARLIER_RECORDING);
		Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));

		recordVideo(file, true);

		assertEquals(PosixFilePermissions.fromString("rw-r-----"), Files.getPosixFilePermissions(file));
	}

	@Test
	void replacingKeepsLink(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("real.mkv");
		Path link = dir.resolve("link.mkv");

		Files.write(file, EARLIER_RECORDING);

		try {
			Files.createSymbolicLink(link, file);
		}
		catch (UnsupportedOperationException | IOException e) {
			Assumptions.abort("No symbolic links here: " + e);
		}

		recordVideo(link, true);

		assertTrue(Files.isSymbolicLink(link));
		assertEquals("vp8", readInfo(file).getVideoCodec());
	}

	@Test
	void replacingKeepsGivenPath(@TempDir Path dir) throws Exception {
		// As on macOS, whose temporary directories lie behind a link.
		Path real = Files.createDirectory(dir.resolve("real"));
		Path linked = dir.resolve("linked");

		try {
			Files.createSymbolicLink(linked, real);
		}
		catch (UnsupportedOperationException | IOException e) {
			Assumptions.abort("No symbolic links here: " + e);
		}

		Path file = linked.resolve("given.mkv");

		Files.write(file, EARLIER_RECORDING);

		try (MediaRecorder recorder = new MediaRecorder(file, true)) {
			assertEquals(file, recorder.getFile());
		}
	}

	@Test
	void replacingRefusesDirectory(@TempDir Path dir) throws Exception {
		Path file = Files.createDirectory(dir.resolve("directory.mkv"));

		assertThrows(IOException.class, () -> new MediaRecorder(file, true));
		assertEquals(Collections.singletonList(file), filesIn(dir));
	}

	@Test
	void recordsMuLawIntoMatroska() throws Exception {
		assertRecordsG711("audio/PCMU", "pcm_mulaw");
	}

	@Test
	void recordsALawIntoMatroska() throws Exception {
		assertRecordsG711("audio/PCMA", "pcm_alaw");
	}

	@Test
	void leavesOutCodecFileCannotHold() throws Exception {
		// MP4 has no place for VP8.
		Path file = tempDir.resolve("audio-only.mp4");
		Listener listener = new Listener();

		try (FakeTrack video = FakeTrack.vp8();
				FakeTrack audio = FakeTrack.opus();
				MediaRecorder recorder = recorder(file)) {
			FakeFeed feed = new FakeFeed(recorder::sync, video, audio);

			recorder.setListener(listener);
			video.addTo(recorder);
			audio.addTo(recorder);
			recorder.start();

			feed.play(seconds(1));

			assertTrue(recorder.stop());

			recorder.sync();
		}

		assertEquals(1, listener.warnings.size(), listener.warnings.toString());
		assertTrue(listener.warnings.get(0).contains("video/VP8"), listener.warnings.get(0));

		MediaInfo info = readInfo(file);

		assertFalse(info.hasVideo());
		assertEquals("opus", info.getAudioCodec());
	}

	@Test
	void deletesFileWithoutMedia() throws Exception {
		Path file = tempDir.resolve("nothing.mkv");

		try (FakeTrack video = FakeTrack.vp8();
				MediaRecorder recorder = recorder(file)) {
			FakeFeed feed = new FakeFeed(recorder::sync);

			assertTrue(Files.exists(file));

			video.addTo(recorder);
			recorder.start();

			feed.play(seconds(1));

			assertFalse(recorder.stop());
			assertEquals(MediaRecorderResult.EMPTY, recorder.getResult());
		}

		assertFalse(Files.exists(file));
	}

	@Test
	void unstartedRecorderDeletesFile() throws Exception {
		Path file = tempDir.resolve("unstarted.mkv");
		MediaRecorder recorder = new MediaRecorder(file);

		try {
			assertEquals(MediaRecorderState.IDLE, recorder.getState());
			assertThrows(IllegalStateException.class, recorder::start);
		}
		finally {
			recorder.close();
		}

		assertFalse(Files.exists(file));

		// Kept beyond the close, with the answer of the stop it made.
		assertEquals(MediaRecorderResult.EMPTY, recorder.getResult());
		assertFalse(recorder.stop());
	}

	@Test
	void pathCopyFails() throws Exception {
		// The native copy of the path fails as an exhausted heap does: the
		// failure reaches Java instead of ending the process, and the
		// characters of the Java string are released all the same.
		Path file = tempDir.resolve("copy-fails.mkv");

		AllocationFault.failNextStringCopy();

		try {
			assertThrows(OutOfMemoryError.class, () -> new MediaRecorder(file));
		}
		finally {
			AllocationFault.disarm();
		}

		assertEquals(0, AllocationFault.heldStrings());
		assertFalse(Files.exists(file));

		try (MediaRecorder recorder = new MediaRecorder(file)) {
			assertEquals(MediaRecorderState.IDLE, recorder.getState());
		}
	}

	@Test
	void rejectsTracksOnceStarted() throws Exception {
		Path file = tempDir.resolve("rejects.mkv");

		try (FakeTrack video = FakeTrack.vp8();
				FakeTrack audio = FakeTrack.opus();
				MediaRecorder recorder = recorder(file)) {
			video.addTo(recorder);
			recorder.start();

			assertThrows(IllegalStateException.class, () -> audio.addTo(recorder));
			assertThrows(IllegalStateException.class, recorder::start);
			assertEquals(0, audio.observers());
		}
	}

	@Test
	void stopFromListener() throws Exception {
		Path file = tempDir.resolve("listener-stop.mkv");
		AtomicReference<Boolean> stopped = new AtomicReference<>();
		CountDownLatch done = new CountDownLatch(1);

		try (FakeTrack video = FakeTrack.vp8();
				MediaRecorder recorder = recorder(file)) {
			FakeFeed feed = new FakeFeed(recorder::sync, video);

			recorder.setListener(new MediaRecorderListener() {

				@Override
				public void onStarted() {
					stopped.set(recorder.stop());
					done.countDown();
				}
			});
			video.addTo(recorder);
			recorder.start();

			feed.play(millis(200));

			assertTrue(done.await(10, TimeUnit.SECONDS), "stopping from the listener hung");
			assertEquals(MediaRecorderState.STOPPED, recorder.getState());
		}

		assertTrue(stopped.get());
		assertTrue(readInfo(file).hasVideo());
	}

	@Test
	void failedAddTrackLeavesRecorderAsItWas() throws Exception {
		Path file = tempDir.resolve("failed-track.mkv");
		Listener listener = new Listener();

		try (FakeTrack video = FakeTrack.vp8();
				MediaRecorder recorder = recorder(file)) {
			FakeFeed feed = new FakeFeed(recorder::sync, video);

			recorder.setListener(listener);

			AllocationFault.failNextAllocation();

			assertThrows(OutOfMemoryError.class, () -> video.addTo(recorder));

			// Not added on either side: there is nothing to start with.
			assertThrows(IllegalStateException.class, recorder::start);

			video.addTo(recorder);
			recorder.start();

			feed.play(seconds(1));

			assertTrue(recorder.stop());

			recorder.sync();
		}
		finally {
			AllocationFault.disarm();
		}

		assertNull(listener.error.get());
		assertStreams(file, video(25, 0, 960));
	}

	@Test
	void failedStartLeavesRecorderIdle() throws Exception {
		Path file = tempDir.resolve("failed-start.mkv");
		Listener listener = new Listener();

		try (FakeTrack video = FakeTrack.vp8();
				MediaRecorder recorder = recorder(file)) {
			FakeFeed feed = new FakeFeed(recorder::sync, video);

			recorder.setListener(listener);
			video.addTo(recorder);

			AllocationFault.failNextThreadStart();

			RuntimeException e = assertThrows(RuntimeException.class, recorder::start);
			assertTrue(e.getMessage().startsWith("Starting a thread failed"), e.getMessage());
			assertEquals(MediaRecorderState.IDLE, recorder.getState());
			assertEquals(0, video.observers());

			// Nothing was left half started, so it starts on a second try.
			recorder.start();

			feed.play(seconds(1));

			assertTrue(recorder.stop());

			recorder.sync();
		}
		finally {
			AllocationFault.disarm();
		}

		assertNull(listener.error.get());
		assertStreams(file, video(25, 0, 960));
	}

	@Test
	void failedAttachLeavesRecorderIdle() throws Exception {
		Path file = tempDir.resolve("failed-attach.mkv");
		Listener listener = new Listener();

		try (FakeTrack video = FakeTrack.vp8();
				FakeTrack audio = FakeTrack.opus();
				MediaRecorder recorder = recorder(file)) {
			FakeFeed feed = new FakeFeed(recorder::sync, video, audio);

			recorder.setListener(listener);
			video.addTo(recorder);
			audio.addTo(recorder);

			// The second track fails, with the first one attached.
			AllocationFault.failObserverAdd(1);

			assertThrows(OutOfMemoryError.class, recorder::start);
			assertFalse(AllocationFault.isArmed());
			assertEquals(MediaRecorderState.IDLE, recorder.getState());
			assertNull(recorder.getResult());
			assertEquals(0, video.observers());
			assertEquals(0, audio.observers());

			// Nothing was left half started, so it starts on a second try,
			// with both tracks.
			recorder.start();

			feed.play(seconds(1));

			assertTrue(recorder.stop());
			assertEquals(MediaRecorderResult.COMPLETE, recorder.getResult());

			recorder.sync();
		}
		finally {
			AllocationFault.disarm();
		}

		assertNull(listener.error.get());
		assertEquals(Collections.emptyList(), listener.warnings);
		assertStreams(file, video(25, 0, 960), audio(50, 0, 980));
	}

	@Test
	void closeAfterFailedAttach() throws Exception {
		Path file = tempDir.resolve("closed-after-failed-attach.mkv");

		try (FakeTrack video = FakeTrack.vp8();
				FakeTrack audio = FakeTrack.opus()) {
			MediaRecorder recorder = recorder(file);

			video.addTo(recorder);
			audio.addTo(recorder);

			AllocationFault.failObserverAdd(1);

			assertThrows(OutOfMemoryError.class, recorder::start);

			recorder.close();

			assertEquals(MediaRecorderResult.EMPTY, recorder.getResult());
			assertFalse(Files.exists(file));

			// An observer left on the first track would call into the
			// recorder freed by now.
			assertEquals(0, video.observers());
			assertEquals(0, audio.observers());

			video.push(FakeFeed.START_US);
			audio.push(FakeFeed.START_US);
		}
		finally {
			AllocationFault.disarm();
		}
	}

	@Test
	void dropsFrameThatCannotBeQueued() throws Exception {
		Path file = tempDir.resolve("dropped-frame.mkv");
		Listener listener = new Listener();

		try (FakeTrack video = FakeTrack.vp8();
				MediaRecorder recorder = recorder(file)) {
			FakeFeed feed = new FakeFeed(recorder::sync, video);

			recorder.setListener(listener);
			video.addTo(recorder);
			recorder.start();

			feed.play(seconds(1));

			// The frame at 1 s cannot be copied, on the thread that pushes it,
			// where nothing may be thrown. The video goes on from the key
			// frame asked for then, the next frame.
			AllocationFault.failNextAllocation();

			feed.play(seconds(1));

			assertFalse(AllocationFault.isArmed());
			assertTrue(recorder.stop());
			assertEquals(MediaRecorderResult.COMPLETE, recorder.getResult());

			MediaRecorderStats.Track track = recorder.getStats().getTracks().get(0);

			assertEquals(1, track.getDroppedAllocationFailed());
			assertEquals(0, track.getDroppedQueueFull());
			assertEquals(0, track.getDroppedAwaitingKeyFrame());
			assertEquals(List.of(FakeFeed.START_US, FakeFeed.START_US + millis(1040)), video.getKeyFrameTimesUs());

			recorder.sync();
		}
		finally {
			AllocationFault.disarm();
		}

		assertNull(listener.error.get());
		assertStreams(file, video(49, 0, 1960));
	}

	@Test
	void countsVideoLostToFullQueue() throws Exception {
		Path file = tempDir.resolve("full-queue.mkv");
		MediaRecorderStats stats;

		try (FakeTrack video = FakeTrack.vp8()) {
			MediaRecorder recorder = recorder(file);
			FakeFeed feed = new FakeFeed(recorder::sync, video);

			video.addTo(recorder);
			recorder.start();

			feed.play(seconds(1));

			// The frame at 1 s finds no room, and the key frame asked for
			// comes with the next. So does the frame at 1.2 s, but the
			// recorder asks once a second at most: the video goes on without
			// it until the request at 2 s brings the next key frame.
			AllocationFault.failNextQueueRoom();
			feed.play(millis(200));
			AllocationFault.failNextQueueRoom();
			feed.playUntil(seconds(3));

			assertTrue(recorder.stop());
			assertEquals(MediaRecorderResult.COMPLETE, recorder.getResult());

			stats = recorder.getStats();

			recorder.close();

			// Kept beyond the close.
			assertEquals(stats.getMaxQueuedBytes(), recorder.getStats().getMaxQueuedBytes());
			assertEquals(1, recorder.getStats().getTracks().size());
		}
		finally {
			AllocationFault.disarm();
		}

		MediaRecorderStats.Track track = stats.getTracks().get(0);

		assertEquals(2, track.getDroppedQueueFull());
		assertEquals(0, track.getDroppedAllocationFailed());
		assertEquals(19, track.getDroppedAwaitingKeyFrame());
		assertEquals(21, track.getDroppedFrames());
		assertTrue(stats.getMaxQueuedBytes() > 0);

		assertStreams(file, video(54, 0, 2960));
	}

	@Test
	void countsAudioLostToAllocation() throws Exception {
		Path file = tempDir.resolve("lost-audio.mkv");

		try (FakeTrack audio = FakeTrack.opus();
				MediaRecorder recorder = recorder(file)) {
			FakeFeed feed = new FakeFeed(recorder::sync, audio);

			audio.addTo(recorder);
			recorder.start();

			feed.play(seconds(1));
			AllocationFault.failNextAllocation();
			feed.play(seconds(1));

			assertTrue(recorder.stop());

			MediaRecorderStats.Track track = recorder.getStats().getTracks().get(0);

			assertEquals(1, track.getDroppedAllocationFailed());
			assertEquals(1, track.getDroppedFrames());
		}
		finally {
			AllocationFault.disarm();
		}

		assertStreams(file, audio(99, 0, 1980));
	}

	@Test
	void statsPerTrack() throws Exception {
		try (FakeTrack video = FakeTrack.vp8();
				FakeTrack audio = FakeTrack.opus();
				MediaRecorder recorder = recorder(tempDir.resolve("stats.mkv"))) {
			assertTrue(recorder.getStats().getTracks().isEmpty());

			video.addTo(recorder);
			audio.addTo(recorder);

			MediaRecorderStats stats = recorder.getStats();

			assertEquals(2, stats.getTracks().size());
			assertEquals(0, stats.getMaxQueuedBytes());
			assertEquals(0, stats.getTracks().get(0).getDroppedFrames());
			assertEquals(0, stats.getTracks().get(1).getDroppedFrames());
		}
	}

	@Test
	void failedHeaderFailsRecording() throws Exception {
		Path file = tempDir.resolve("failed-header.mkv");
		Listener listener = new Listener();

		WriteFault.failHeader();

		try (FakeTrack video = FakeTrack.vp8();
				MediaRecorder recorder = recorder(file)) {
			FakeFeed feed = new FakeFeed(recorder::sync, video);

			recorder.setListener(listener);
			video.addTo(recorder);
			recorder.start();

			feed.play(seconds(1));

			// Nothing playable went into the file, so it is not kept.
			assertFalse(recorder.stop());
			assertEquals(MediaRecorderResult.FAILED, recorder.getResult());

			recorder.sync();
		}
		finally {
			WriteFault.disarm();
		}

		assertErrorStartsWith(listener, "Writing the header of ");
		assertEquals(1, listener.started.getCount(), "reported as started");
		assertFalse(Files.exists(file));
	}

	@Test
	void failedWriteKeepsPartialRecording() throws Exception {
		Path file = tempDir.resolve("failed-write.mkv");
		Listener listener = new Listener();

		// Well into the recording, once there is media in the file.
		WriteFault.failPacket(30);

		try (FakeTrack video = FakeTrack.vp8();
				MediaRecorder recorder = recorder(file)) {
			FakeFeed feed = new FakeFeed(recorder::sync, video);

			recorder.setListener(listener);
			video.addTo(recorder);
			recorder.start();

			feed.play(seconds(2));

			assertTrue(recorder.stop());
			assertEquals(MediaRecorderResult.PARTIAL, recorder.getResult());

			recorder.sync();
		}
		finally {
			WriteFault.disarm();
		}

		assertErrorStartsWith(listener, "Writing to ");
		assertTrue(readInfo(file).hasVideo());
	}

	@Test
	void writerOutOfMemoryFailsRecording() throws Exception {
		Path file = tempDir.resolve("writer-oom-early.mkv");
		Listener listener = new Listener();

		// The first frame, which is held back until the header is written.
		AllocationFault.failWriterAllocation(0);

		try (FakeTrack video = FakeTrack.vp8();
				MediaRecorder recorder = recorder(file)) {
			FakeFeed feed = new FakeFeed(recorder::sync, video);

			recorder.setListener(listener);
			video.addTo(recorder);
			recorder.start();

			feed.play(seconds(1));

			assertFalse(AllocationFault.isArmed());
			assertFalse(recorder.stop());
			assertEquals(MediaRecorderResult.FAILED, recorder.getResult());

			recorder.sync();
		}
		finally {
			AllocationFault.disarm();
		}

		assertErrorStartsWith(listener, "Recording to " + file.toAbsolutePath() + " failed: ");
		assertEquals(1, listener.started.getCount(), "reported as started");
		assertFalse(Files.exists(file));
	}

	@Test
	void writerOutOfMemoryKeepsPartialRecording() throws Exception {
		Path file = tempDir.resolve("writer-oom-late.mkv");
		Listener listener = new Listener();

		// Well into the recording, once there is media in the file.
		AllocationFault.failWriterAllocation(30);

		try (FakeTrack video = FakeTrack.vp8();
				MediaRecorder recorder = recorder(file)) {
			FakeFeed feed = new FakeFeed(recorder::sync, video);

			recorder.setListener(listener);
			video.addTo(recorder);
			recorder.start();

			feed.play(seconds(2));

			assertEquals(0, listener.started.getCount(), "not started");

			// The writer has ended, and the file was finished then.
			assertTrue(recorder.stop());
			assertEquals(MediaRecorderResult.PARTIAL, recorder.getResult());

			recorder.sync();
		}
		finally {
			AllocationFault.disarm();
		}

		assertErrorStartsWith(listener, "Recording to " + file.toAbsolutePath() + " failed: ");
		assertTrue(readInfo(file).hasVideo());
	}

	@Test
	void failedTrailerKeepsPartialRecording() throws Exception {
		assertFailedFinishKeepsFile("failed-trailer.mkv", WriteFault::failTrailer, "Finishing ");
	}

	@Test
	void failedCloseKeepsPartialRecording() throws Exception {
		assertFailedFinishKeepsFile("failed-close.mkv", WriteFault::failClose, "Closing ");
	}

	/**
	 * Records a second of video whose file fails to be finished or closed
	 * when the recorder stops: the failure is reported, and the file is kept
	 * as partial.
	 */
	private void assertFailedFinishKeepsFile(String name, Runnable fault, String error) throws Exception {
		Path file = tempDir.resolve(name);
		Listener listener = new Listener();

		fault.run();

		try (FakeTrack video = FakeTrack.vp8();
				MediaRecorder recorder = recorder(file)) {
			FakeFeed feed = new FakeFeed(recorder::sync, video);

			recorder.setListener(listener);
			video.addTo(recorder);
			recorder.start();

			feed.play(seconds(1));

			assertNull(listener.error.get());
			assertTrue(recorder.stop());
			assertEquals(MediaRecorderResult.PARTIAL, recorder.getResult());

			recorder.sync();
		}
		finally {
			WriteFault.disarm();
		}

		assertErrorStartsWith(listener, error + file.toAbsolutePath());
		assertTrue(Files.exists(file));
	}

	/**
	 * Records a second of video into the given file.
	 */
	private static void recordVideo(Path file, boolean overwrite) throws Exception {
		Listener listener = new Listener();

		try (FakeTrack video = FakeTrack.vp8();
				MediaRecorder recorder = recorder(file, overwrite)) {
			FakeFeed feed = new FakeFeed(recorder::sync, video);

			recorder.setListener(listener);
			video.addTo(recorder);
			recorder.start();

			feed.play(seconds(1));

			assertTrue(recorder.stop());
			assertEquals(MediaRecorderResult.COMPLETE, recorder.getResult());

			recorder.sync();
		}

		assertNull(listener.error.get());
	}

	/**
	 * Records G.711 into Matroska, which has no codec ID of its own for it,
	 * and reads the audio back as the codec it was sent in.
	 */
	private void assertRecordsG711(String mimeType, String codecName) throws Exception {
		Path file = tempDir.resolve(codecName + ".mkv");
		Listener listener = new Listener();

		try (FakeTrack video = FakeTrack.vp8();
				FakeTrack audio = FakeTrack.g711(mimeType);
				MediaRecorder recorder = recorder(file)) {
			FakeFeed feed = new FakeFeed(recorder::sync, video, audio);

			recorder.setListener(listener);
			video.addTo(recorder);
			audio.addTo(recorder);
			recorder.start();

			feed.play(seconds(1));

			assertTrue(recorder.stop());

			recorder.sync();
		}

		assertNull(listener.error.get());
		assertEquals(Collections.emptyList(), listener.warnings);
		assertStreams(file, video(25, 0, 960), audio(50, 0, 980));

		MediaInfo info = readInfo(file);

		assertEquals("vp8", info.getVideoCodec());
		assertEquals(codecName, info.getAudioCodec());
		assertEquals(8000, info.getSampleRate());
		assertEquals(1, info.getChannels());
	}

	private static MediaRecorder recorder(Path file) throws IOException {
		return recorder(file, false);
	}

	private static MediaRecorder recorder(Path file, boolean overwrite) throws IOException {
		return new MediaRecorder(file, overwrite, FakeMedia.tableAddress());
	}

	private static List<Path> filesIn(Path dir) {
		try (Stream<Path> files = Files.list(dir)) {
			return files.collect(Collectors.toList());
		}
		catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static void assertErrorStartsWith(Listener listener, String prefix) {
		String error = listener.error.get();

		assertTrue(error != null && error.startsWith(prefix), "error: " + error);
	}

	private static MediaInfo readInfo(Path file) throws Exception {
		try (MediaReader reader = new MediaReader(file)) {
			return reader.getInfo();
		}
	}


	private static class Listener implements MediaRecorderListener {

		final CountDownLatch started = new CountDownLatch(1);

		final List<String> warnings = new CopyOnWriteArrayList<>();

		final AtomicReference<String> error = new AtomicReference<>();


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
			error.set(message);
		}
	}

}
