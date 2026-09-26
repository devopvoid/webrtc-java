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

package dev.onvoid.webrtc.media.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSink;
import dev.onvoid.webrtc.media.audio.CustomAudioSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Tests playing live streams over RTSP, from a server in this process, and
 * what keeps a stream that misbehaves from blocking: the timeouts, and closing
 * a player that is waiting on one.
 *
 * @author Alex Andres
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class RtspStreamTest {

	private AudioDeviceModule audioModule;
	private PeerConnectionFactory factory;


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
	void describesStream() throws Exception {
		try (TestRtspServer server = new TestRtspServer(TestRtspServer.Behavior.END, 100);
			 MediaReader reader = new MediaReader(server.url())) {
			MediaInfo info = reader.getInfo();

			assertFalse(info.hasVideo());
			assertTrue(info.hasAudio());
			assertEquals(TestRtspServer.SAMPLE_RATE, info.getSampleRate());
			assertEquals(1, info.getChannels());
		}
	}

	@Test
	void playsStream() throws Exception {
		try (TestRtspServer server = new TestRtspServer(TestRtspServer.Behavior.END, 100);
			 Playback playback = new Playback(new MediaReader(server.url()))) {
			playback.player.play();

			// One second of audio, sent in real time, and the end of the
			// stream once the server hangs up.
			assertTrue(playback.done.await(10, TimeUnit.SECONDS), "stream did not end");
			assertTrue(playback.chunks.get() >= 90, "audio chunks: " + playback.chunks.get());
			assertTrue(playback.nonSilent.get() > 0, "only silence arrived");
		}
	}

	@Test
	void liveStreamEndsDespiteLooping() throws Exception {
		try (TestRtspServer server = new TestRtspServer(TestRtspServer.Behavior.END, 50);
			 Playback playback = new Playback(new MediaReader(server.url()))) {
			// A live stream cannot start over, so looping it has to end like
			// any stream, rather than try to rewind forever.
			playback.player.setLooping(true);
			playback.player.play();

			assertTrue(playback.done.await(10, TimeUnit.SECONDS), "stream did not end");
			assertEquals(MediaPlayerState.ENDED, playback.player.getState(),
					"error: " + playback.error.get());
		}
	}

	@Test
	void openTimesOut() throws Exception {
		try (TestRtspServer server = new TestRtspServer(TestRtspServer.Behavior.SILENT, 0)) {
			long start = System.nanoTime();

			IOException e = assertThrows(IOException.class,
					() -> new MediaReader(server.url(), Duration.ofMillis(500)));

			long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

			assertTrue(elapsedMs < 5000, "took " + elapsedMs + " ms");
			assertTrue(e.getMessage().toLowerCase().contains("timed out"), e.getMessage());
		}
	}

	@Test
	void errorHidesCredentials() throws Exception {
		try (TestRtspServer server = new TestRtspServer(TestRtspServer.Behavior.SILENT, 0)) {
			String url = server.url().replace("rtsp://", "rtsp://admin:s3cret@");

			IOException e = assertThrows(IOException.class,
					() -> new MediaReader(url, Duration.ofMillis(300)));

			// Messages end up in logs; the password must not.
			assertFalse(e.getMessage().contains("s3cret"), e.getMessage());
			assertFalse(e.getMessage().contains("admin"), e.getMessage());
			assertTrue(e.getMessage().contains("127.0.0.1:" + server.port() + "/stream"),
					e.getMessage());
		}
	}

	@Test
	void stalledStreamTimesOut() throws Exception {
		try (TestRtspServer server = new TestRtspServer(TestRtspServer.Behavior.STALL, 30);
			 Playback playback = new Playback(new MediaReader(server.url(), Duration.ofSeconds(1)))) {
			playback.player.play();

			assertTrue(playback.done.await(10, TimeUnit.SECONDS), "stall not reported");

			String error = playback.error.get();

			assertTrue(error != null && error.toLowerCase().contains("timed out"),
					"error: " + error);
			assertEquals(MediaPlayerState.PAUSED, playback.player.getState());

			// Reported, so not an error of the playback itself.
			playback.error.set(null);
		}
	}

	@Test
	void closeInterruptsStalledStream() throws Exception {
		try (TestRtspServer server = new TestRtspServer(TestRtspServer.Behavior.STALL, 30);
			 Playback playback = new Playback(new MediaReader(server.url(), Duration.ofMinutes(5)))) {
			playback.player.play();

			// Let it run into the stall, where the player's thread waits on a
			// read that would otherwise take five minutes to give up.
			assertTrue(waitFor(() -> playback.chunks.get() >= 25, 10), "stream did not start");
			Thread.sleep(500);

			long start = System.nanoTime();

			playback.player.close();

			long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

			assertTrue(elapsedMs < 3000, "close took " + elapsedMs + " ms");
		}
	}

	@Test
	void refusesOtherProtocols() throws Exception {
		// The build carries HTTP for RTSP tunnelled over it, but a source must
		// not be able to use it on its own.
		try (TestRtspServer server = new TestRtspServer(TestRtspServer.Behavior.SILENT, 0)) {
			assertThrows(IOException.class, () ->
					new MediaReader("http://127.0.0.1:" + server.port() + "/file.mp4",
							Duration.ofSeconds(2)));

			assertEquals(0, server.connections(), "the server was contacted");
		}
	}

	private static boolean waitFor(java.util.function.BooleanSupplier condition,
			int seconds) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);

		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				return false;
			}

			Thread.sleep(20);
		}

		return true;
	}

	/**
	 * An audio source fed by a player, and the track sink that counts what
	 * arrives.
	 */
	private final class Playback implements AutoCloseable {

		final CustomAudioSource audioSource = new CustomAudioSource();
		final AudioTrack audioTrack = factory.createAudioTrack("audio", audioSource);
		final MediaPlayer player;

		final CountDownLatch done = new CountDownLatch(1);
		final AtomicInteger chunks = new AtomicInteger();
		final AtomicInteger nonSilent = new AtomicInteger();
		final AtomicReference<String> error = new AtomicReference<>();

		private final AudioTrackSink sink = (data, bits, rate, channels, frames) -> {
			chunks.incrementAndGet();

			for (byte b : data) {
				if (b != 0) {
					nonSilent.incrementAndGet();
					break;
				}
			}
		};


		Playback(MediaReader reader) throws IOException {
			audioTrack.addSink(sink);

			player = new MediaPlayer(reader, null, audioSource);
			player.setListener(new MediaPlayerListener() {

				@Override
				public void onEndOfStream() {
					done.countDown();
				}

				@Override
				public void onError(String message) {
					error.set(message);
					done.countDown();
				}
			});
		}

		@Override
		public void close() {
			player.close();

			audioTrack.removeSink(sink);
			audioTrack.dispose();
			audioSource.dispose();

			String message = error.get();

			if (message != null) {
				throw new AssertionError("playback reported an error: " + message);
			}
		}
	}

}
