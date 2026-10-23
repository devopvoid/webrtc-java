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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import dev.onvoid.webrtc.client.AudioMode;
import dev.onvoid.webrtc.client.CustomAudio;
import dev.onvoid.webrtc.client.CustomVideo;
import dev.onvoid.webrtc.client.PcmFormat;
import dev.onvoid.webrtc.client.Peer;
import dev.onvoid.webrtc.client.PeerOptions;
import dev.onvoid.webrtc.client.RemoteTrack;
import dev.onvoid.webrtc.client.Sender;
import dev.onvoid.webrtc.client.Signaling;
import dev.onvoid.webrtc.client.SignalingPair;
import dev.onvoid.webrtc.client.WebRtc;
import dev.onvoid.webrtc.client.internal.EncodedFrames;
import dev.onvoid.webrtc.media.player.MediaInfo;
import dev.onvoid.webrtc.media.player.MediaReader;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Records a call of the client API, from its senders and from its remote
 * tracks.
 *
 * @author Alex Andres
 */
@Execution(ExecutionMode.SAME_THREAD)
class ClientApiRecorderTest {

	private static final int WIDTH = 320;

	private static final int HEIGHT = 240;

	private static final long RECORD_MS = 2000;

	@TempDir
	Path tempDir;

	private final List<Throwable> errors = new CopyOnWriteArrayList<>();

	private WebRtc rtc;

	private ScheduledExecutorService feeder;

	private Peer caller;

	private Sender videoSender;

	private Sender audioSender;

	private final CompletableFuture<RemoteTrack> remoteVideo = new CompletableFuture<>();

	private final CompletableFuture<RemoteTrack> remoteAudio = new CompletableFuture<>();


	@BeforeEach
	void startCall() throws Exception {
		rtc = WebRtc.builder().audio(AudioMode.HEADLESS).errorHandler(errors::add).build();
		feeder = Executors.newSingleThreadScheduledExecutor();

		SignalingPair link = Signaling.loopback();
		caller = rtc.createPeer(PeerOptions.builder().signaling(link.first()).build());
		Peer callee = rtc.createPeer(PeerOptions.builder().signaling(link.second()).polite(true).build());

		callee.onTrack(track -> (track.isVideo() ? remoteVideo : remoteAudio).complete(track));

		CustomVideo video = rtc.media().customVideo("video");
		CustomAudio audio = rtc.media().customAudio("audio");
		videoSender = caller.addTrack(video);
		audioSender = caller.addTrack(audio);

		feeder.scheduleAtFixedRate(() -> {
			NativeI420Buffer buffer = NativeI420Buffer.allocate(WIDTH, HEIGHT);
			ByteBuffer y = buffer.getDataY();
			for (int i = 0; i < y.capacity(); i++) {
				y.put(i, (byte) (i + System.nanoTime() / 10_000_000));
			}
			VideoFrame frame = new VideoFrame(buffer, System.nanoTime());
			video.push(frame);
			frame.release();
		}, 0, 33, TimeUnit.MILLISECONDS);
		feeder.scheduleAtFixedRate(() -> audio.push(new byte[48000 / 10 * 2], PcmFormat.of(48000, 1)),
				0, 100, TimeUnit.MILLISECONDS);

		caller.whenConnected().get(15, TimeUnit.SECONDS);
		remoteVideo.get(15, TimeUnit.SECONDS);
		remoteAudio.get(15, TimeUnit.SECONDS);

		// Well into the call, so that the recorder has to ask for a key frame.
		Thread.sleep(1500);
	}

	@AfterEach
	void endCall() throws Exception {
		feeder.shutdownNow();
		feeder.awaitTermination(5, TimeUnit.SECONDS);
		rtc.close();

		assertTrue(errors.isEmpty(), () -> "Errors: " + errors);
	}

	@Test
	void recordsRemoteTracks() throws Exception {
		Path file = tempDir.resolve("received.mkv");

		record(file, recorder -> {
			recorder.addTrack(remoteVideo.join());
			recorder.addTrack(remoteAudio.join());
		});

		MediaInfo info = readInfo(file);

		assertTrue(info.hasVideo());
		assertEquals(WIDTH, info.getVideoWidth());
		assertEquals(HEIGHT, info.getVideoHeight());
		assertEquals("opus", info.getAudioCodec());
	}

	@Test
	void recordsSenders() throws Exception {
		// Matroska, since the codec follows the machine: VP8 here, H.264 where
		// a hardware encoder comes first, as on macOS.
		Path file = tempDir.resolve("sent.mkv");

		record(file, recorder -> {
			recorder.addTrack(videoSender);
			recorder.addTrack(audioSender);
		});

		MediaInfo info = readInfo(file);

		assertTrue(info.hasVideo());
		assertEquals("opus", info.getAudioCodec());
	}

	@Test
	void peerClosedWhileRecording() throws Exception {
		Path file = tempDir.resolve("cut.mkv");
		CountDownLatch started = new CountDownLatch(1);

		try (MediaRecorder recorder = new MediaRecorder(file)) {
			recorder.setListener(new MediaRecorderListener() {

				@Override
				public void onStarted() {
					started.countDown();
				}
			});

			recorder.addTrack(remoteVideo.join());
			recorder.addTrack(videoSender);
			recorder.start();

			assertTrue(started.await(10, TimeUnit.SECONDS), "not started");
			Thread.sleep(500);

			// The tracks end in the recording; the file still finishes.
			caller.closeAsync().get(10, TimeUnit.SECONDS);
			Thread.sleep(500);

			assertTrue(recorder.stop());
		}

		assertTrue(readInfo(file).hasVideo());
	}

	@Test
	void removedSenderRefused() throws Exception {
		videoSender.remove().get(10, TimeUnit.SECONDS);

		try (MediaRecorder recorder = new MediaRecorder(tempDir.resolve("refused.mkv"))) {
			assertThrows(IllegalArgumentException.class, () -> recorder.addTrack(videoSender));
		}
	}

	@Test
	void interruptedWaitCancels() {
		// The peer has not handed the source over yet; once it does, it finds
		// the future cancelled and closes the source itself.
		CompletableFuture<EncodedFrames.Source> pending = new CompletableFuture<>();

		Thread.currentThread().interrupt();

		assertThrows(IllegalStateException.class, () -> MediaRecorder.sourceOf(pending));
		assertTrue(Thread.interrupted());
		assertTrue(pending.isCancelled());
	}

	@Test
	void sourceBeforeGivingUpClosed() throws Exception {
		// The source arrived just as the recorder stopped waiting, so no
		// recorder takes it over, and giving up drops its reference.
		CompletableFuture<EncodedFrames.Source> frames = EncodedFrames.of(videoSender);
		EncodedFrames.Source source = frames.get(10, TimeUnit.SECONDS);

		MediaRecorder.giveUp(frames);

		assertThrows(IllegalStateException.class, source::take);
	}

	private interface Tracks {

		void addTo(MediaRecorder recorder);

	}

	private static void record(Path file, Tracks tracks) throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		AtomicReference<String> error = new AtomicReference<>();

		try (MediaRecorder recorder = new MediaRecorder(file)) {
			recorder.setListener(new MediaRecorderListener() {

				@Override
				public void onStarted() {
					started.countDown();
				}

				@Override
				public void onError(String message) {
					error.set(message);
				}
			});

			tracks.addTo(recorder);
			recorder.start();

			assertTrue(started.await(10, TimeUnit.SECONDS), "not started");
			Thread.sleep(RECORD_MS);
			assertTrue(recorder.stop());
		}

		assertNull(error.get());
	}

	private static MediaInfo readInfo(Path file) throws Exception {
		try (MediaReader reader = new MediaReader(file)) {
			return reader.getInfo();
		}
	}

}
