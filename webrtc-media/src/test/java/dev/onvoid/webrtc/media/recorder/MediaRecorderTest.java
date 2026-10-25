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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.RTCRtpReceiver;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;
import dev.onvoid.webrtc.media.player.AllocationFault;
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
 * Tests recording a call between two local peer connections, checked by
 * reading the recorded file back.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class MediaRecorderTest {

	private static final long RECORD_MS = 3000;

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
	void recordsReceivedCallIntoMatroska() throws Exception {
		Path file = tempDir.resolve("received.mkv");
		Listener listener = new Listener();

		try (TestCall call = new TestCall(factory)) {
			// Well into the call, so that the receiver has had its key frame
			// long ago and the recorder has to ask for one.
			Thread.sleep(1500);

			RTCRtpReceiver video = call.getReceiver(0);
			RTCRtpReceiver audio = call.getReceiver(1);

			try (MediaRecorder recorder = new MediaRecorder(file)) {
				recorder.setListener(listener);
				recorder.addTrack(video);
				recorder.addTrack(audio);
				recorder.start();

				assertEquals(MediaRecorderState.RECORDING, recorder.getState());
				assertTrue(listener.started.await(10, TimeUnit.SECONDS), "not started");

				Thread.sleep(RECORD_MS);

				assertTrue(recorder.stop());
				assertEquals(MediaRecorderState.STOPPED, recorder.getState());
			}
			finally {
				video.dispose();
				audio.dispose();
			}
		}

		assertNull(listener.error.get());
		assertTrue(listener.warnings.isEmpty(), listener.warnings.toString());

		MediaInfo info = readInfo(file);

		assertTrue(info.hasVideo());
		assertTrue(info.hasAudio());
		assertEquals("vp8", info.getVideoCodec());
		assertEquals(TestCall.WIDTH, info.getVideoWidth());
		assertEquals(TestCall.HEIGHT, info.getVideoHeight());
		assertEquals("opus", info.getAudioCodec());
		assertEquals(48000, info.getSampleRate());
		assertDuration(info);
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
		assertDuration(info);
	}

	@Test
	void recordsMuLawIntoMatroska() throws Exception {
		assertRecordsG711("PCMU", "pcm_mulaw");
	}

	@Test
	void recordsALawIntoMatroska() throws Exception {
		assertRecordsG711("PCMA", "pcm_alaw");
	}

	@Test
	void leavesOutCodecFileCannotHold() throws Exception {
		// MP4 has no place for VP8, which the call sends.
		Path file = tempDir.resolve("audio-only.mp4");
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

		assertFalse(listener.warnings.isEmpty());
		assertTrue(listener.warnings.get(0).contains("video/VP8"), listener.warnings.get(0));

		MediaInfo info = readInfo(file);

		assertFalse(info.hasVideo());
		assertEquals("opus", info.getAudioCodec());
	}

	@Test
	void deletesFileWithoutMedia() throws Exception {
		Path file = tempDir.resolve("nothing.mkv");

		try (TestCall call = new TestCall(factory);
				MediaRecorder recorder = new MediaRecorder(file)) {
			assertTrue(Files.exists(file));

			recorder.addTrack(call.getVideoSender());
			recorder.start();

			// Too soon for any frame to have made it into the file.
			assertFalse(recorder.stop());
		}

		assertFalse(Files.exists(file));
	}

	@Test
	void unstartedRecorderDeletesFile() throws Exception {
		Path file = tempDir.resolve("unstarted.mkv");

		try (MediaRecorder recorder = new MediaRecorder(file)) {
			assertEquals(MediaRecorderState.IDLE, recorder.getState());
			assertThrows(IllegalStateException.class, recorder::start);
		}

		assertFalse(Files.exists(file));
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

		try (TestCall call = new TestCall(factory);
				MediaRecorder recorder = new MediaRecorder(file)) {
			recorder.addTrack(call.getVideoSender());
			recorder.start();

			assertThrows(IllegalStateException.class, () -> recorder.addTrack(call.getAudioSender()));
			assertThrows(IllegalStateException.class, recorder::start);
		}
	}

	@Test
	void stopFromListener() throws Exception {
		Path file = tempDir.resolve("listener-stop.mkv");
		AtomicReference<Boolean> stopped = new AtomicReference<>();
		CountDownLatch done = new CountDownLatch(1);

		try (TestCall call = new TestCall(factory);
				MediaRecorder recorder = new MediaRecorder(file)) {
			recorder.setListener(new MediaRecorderListener() {

				@Override
				public void onStarted() {
					stopped.set(recorder.stop());
					done.countDown();
				}
			});
			recorder.addTrack(call.getVideoSender());
			recorder.start();

			assertTrue(done.await(10, TimeUnit.SECONDS), "stopping from the listener hung");
		}

		assertTrue(stopped.get());
		assertTrue(readInfo(file).hasVideo());
	}

	@Test
	void failedAddTrackLeavesRecorderAsItWas() throws Exception {
		Path file = tempDir.resolve("failed-track.mkv");
		Listener listener = new Listener();

		try (TestCall call = new TestCall(factory);
				MediaRecorder recorder = new MediaRecorder(file)) {
			recorder.setListener(listener);

			AllocationFault.failNextAllocation();

			assertThrows(OutOfMemoryError.class, () -> recorder.addTrack(call.getVideoSender()));

			// Not added on either side: there is nothing to start with.
			assertThrows(IllegalStateException.class, recorder::start);

			recorder.addTrack(call.getVideoSender());
			recorder.start();

			assertTrue(listener.started.await(10, TimeUnit.SECONDS), "not started");
			assertTrue(recorder.stop());
		}
		finally {
			AllocationFault.disarm();
		}

		assertNull(listener.error.get());
		assertTrue(readInfo(file).hasVideo());
	}

	@Test
	void failedStartLeavesRecorderIdle() throws Exception {
		Path file = tempDir.resolve("failed-start.mkv");
		Listener listener = new Listener();

		try (TestCall call = new TestCall(factory);
				MediaRecorder recorder = new MediaRecorder(file)) {
			recorder.setListener(listener);
			recorder.addTrack(call.getVideoSender());

			AllocationFault.failNextThreadStart();

			RuntimeException e = assertThrows(RuntimeException.class, recorder::start);
			assertTrue(e.getMessage().startsWith("Starting a thread failed"), e.getMessage());
			assertEquals(MediaRecorderState.IDLE, recorder.getState());

			// Nothing was left half started, so it starts on a second try.
			recorder.start();

			assertTrue(listener.started.await(10, TimeUnit.SECONDS), "not started");
			assertTrue(recorder.stop());
		}
		finally {
			AllocationFault.disarm();
		}

		assertNull(listener.error.get());
		assertTrue(readInfo(file).hasVideo());
	}

	@Test
	void dropsFrameThatCannotBeQueued() throws Exception {
		Path file = tempDir.resolve("dropped-frame.mkv");
		Listener listener = new Listener();

		try (TestCall call = new TestCall(factory);
				MediaRecorder recorder = new MediaRecorder(file)) {
			recorder.setListener(listener);
			recorder.addTrack(call.getVideoSender());
			recorder.start();

			assertTrue(listener.started.await(10, TimeUnit.SECONDS), "not started");

			// The next frame cannot be copied, on a WebRTC thread, where
			// nothing may be thrown.
			AllocationFault.failNextAllocation();

			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

			while (AllocationFault.isArmed() && System.nanoTime() < deadline) {
				Thread.sleep(10);
			}

			assertFalse(AllocationFault.isArmed(), "no frame arrived");

			Thread.sleep(1000);

			assertTrue(recorder.stop());
		}
		finally {
			AllocationFault.disarm();
		}

		assertNull(listener.error.get());
		assertTrue(readInfo(file).hasVideo());
	}

	/**
	 * Records a call that sends G.711 into Matroska, which has no codec ID of
	 * its own for it, and reads the audio back as the codec it was sent in.
	 */
	private void assertRecordsG711(String sdpName, String codecName) throws Exception {
		Path file = tempDir.resolve(sdpName + ".mkv");
		Listener listener = new Listener();

		try (TestCall call = new TestCall(factory, sdpName);
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
		assertTrue(listener.warnings.isEmpty(), listener.warnings.toString());

		MediaInfo info = readInfo(file);

		assertEquals("vp8", info.getVideoCodec());
		assertEquals(codecName, info.getAudioCodec());
		assertEquals(8000, info.getSampleRate());
		assertEquals(1, info.getChannels());
		assertDuration(info);
	}

	private static MediaInfo readInfo(Path file) throws Exception {
		try (MediaReader reader = new MediaReader(file)) {
			return reader.getInfo();
		}
	}

	private static void assertDuration(MediaInfo info) {
		long durationMs = info.getDurationUs() / 1000;

		// The file begins when the first key frame arrives, somewhat after
		// the start, and ends with what was queued at the stop.
		assertTrue(durationMs > RECORD_MS - 1000 && durationMs < RECORD_MS + 2000,
				"duration: " + durationMs + " ms");
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
