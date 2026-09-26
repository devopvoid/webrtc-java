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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSink;
import dev.onvoid.webrtc.media.audio.CustomAudioSource;
import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Tests playing a media source into custom media sources. The media is
 * observed where an application would see it, on the sinks of the tracks made
 * from those sources, so what these assert is what a peer connection would be
 * sent.
 * <p>
 * The asset runs three seconds and is played in real time, so these tests take
 * about as long as the media they play.
 *
 * @author Alex Andres
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class MediaPlayerTest {

	/**
	 * The committed asset: 320x240 VP8 at 15 fps, 48 kHz mono Opus, 3.008 s,
	 * with a keyframe every second so that seeking has somewhere to land.
	 */
	private static final String ASSET = "/media-test.webm";

	/** 3.008 seconds at 15 frames a second. */
	private static final int EXPECTED_FRAMES = 45;

	/** 3.008 seconds in chunks of 10 ms. */
	private static final int EXPECTED_CHUNKS = 300;

	private AudioDeviceModule audioModule;
	private PeerConnectionFactory factory;


	@BeforeAll
	void initFactory() {
		// A dummy audio layer, because this factory sends pushed audio and
		// must not also be capturing from a device.
		audioModule = new AudioDeviceModule(AudioLayer.kDummyAudio);
		factory = new PeerConnectionFactory(audioModule);
	}

	@AfterAll
	void disposeFactory() {
		factory.dispose();
		audioModule.dispose();
	}

	@Test
	void playsToEnd() throws Exception {
		try (Playback playback = new Playback()) {
			playback.player.play();

			assertTrue(playback.ended.await(15, TimeUnit.SECONDS), "no end of stream");

			// Allowing one frame either way: which side of the last frame
			// interval the file ends on is the container's business.
			assertTrue(Math.abs(playback.frames.get() - EXPECTED_FRAMES) <= 1,
					"video frames: " + playback.frames.get());
			assertTrue(Math.abs(playback.chunks.get() - EXPECTED_CHUNKS) <= 2,
					"audio chunks: " + playback.chunks.get());

			assertEquals(MediaPlayerState.ENDED, playback.player.getState());
		}
	}

	@Test
	void deliversWhatWebRtcTakes() throws Exception {
		try (Playback playback = new Playback()) {
			playback.player.play();

			assertTrue(playback.ended.await(15, TimeUnit.SECONDS), "no end of stream");

			assertEquals(320, playback.width.get());
			assertEquals(240, playback.height.get());

			// The audio has to arrive as 10 ms of 48 kHz PCM whatever the
			// source was, which for this asset means it was resampled.
			assertEquals(48000, playback.sampleRate.get());
			assertEquals(1, playback.channels.get());
			assertEquals(480, playback.framesPerChunk.get());
		}
	}

	@Test
	void playsAvi() throws Exception {
		MediaReader reader = new MediaReader(MediaReaderTest.asset(MediaReaderTest.AVI_ASSET));

		// Opening the player is what fails when a decoder for the file's
		// codecs was left out of the FFmpeg build.
		try (Playback playback = new Playback(reader)) {
			playback.player.play();

			assertTrue(playback.ended.await(15, TimeUnit.SECONDS), "no end of stream");

			// The asset holds six frames, all of which have to come out.
			assertEquals(6, playback.frames.get());
			assertEquals(1920, playback.width.get());
			assertEquals(1080, playback.height.get());

			assertTrue(playback.chunks.get() > 0, "no audio");
			assertEquals(2, playback.channels.get());
		}
	}

	@Test
	void coarseInterleavingKeepsVideoEven() throws Exception {
		// All three seconds of audio are stored ahead of the video, so the
		// video can only be decoded once the audio has been read. A player
		// that stops reading while the audio queue is full gets to the video
		// seconds late and delivers what is overdue in a burst that WebRTC's
		// encoder answers by dropping all but the last frame of it.
		MediaReader reader = new MediaReader(
				MediaReaderTest.asset(MediaReaderTest.COARSE_ASSET));

		try (Playback playback = new Playback(reader)) {
			playback.player.play();

			assertTrue(playback.ended.await(15, TimeUnit.SECONDS), "no end of stream");

			List<Long> times;
			synchronized (playback.frameTimes) {
				times = new ArrayList<>(playback.frameTimes);
			}

			assertEquals(75, times.size());

			// Frame i and the first audio chunk share a timeline, so frame i
			// is due I frame intervals after that chunk arrived. How late the
			// worst frame is tells a player that starved its video, seconds
			// behind, from a busy machine that was merely slow for a moment,
			// which a count of closely spaced frames cannot.
			long start = Math.min(playback.firstChunkNs.get(), times.get(0));
			long frameNs = TimeUnit.MILLISECONDS.toNanos(40);
			long maxLateNs = 0;
			StringBuilder offsets = new StringBuilder();

			for (int i = 0; i < times.size(); i++) {
				long offsetNs = times.get(i) - start;

				maxLateNs = Math.max(maxLateNs, offsetNs - i * frameNs);
				offsets.append(i == 0 ? "" : " ").append(offsetNs / 1_000_000);
			}

			assertTrue(maxLateNs < TimeUnit.SECONDS.toNanos(1),
					"video was up to " + maxLateNs / 1_000_000 + " ms late; frames arrived at "
							+ offsets + " ms after the first audio");
		}
	}

	@Test
	void pacesInRealTime() throws Exception {
		try (Playback playback = new Playback()) {
			long started = System.nanoTime();

			playback.player.play();

			assertTrue(playback.ended.await(15, TimeUnit.SECONDS), "no end of stream");

			long elapsedMs = (System.nanoTime() - started) / 1_000_000;

			// Three seconds of media takes three seconds to play. The upper
			// bound is loose because a busy machine can only ever be late.
			assertTrue(elapsedMs > 2500, "finished too early: " + elapsedMs + " ms");
			assertTrue(elapsedMs < 8000, "finished too late: " + elapsedMs + " ms");
		}
	}

	@Test
	void loopsWithoutEnding() throws Exception {
		try (Playback playback = new Playback()) {
			playback.player.setLooping(true);
			playback.player.play();

			// Well past the length of the asset, so a player that did not
			// start over would have run out by now.
			assertFalse(playback.ended.await(4500, TimeUnit.MILLISECONDS),
					"a looping player reported an end of stream");

			assertTrue(playback.frames.get() > EXPECTED_FRAMES,
					"stopped at the end of the first pass: " + playback.frames.get());
			assertEquals(MediaPlayerState.PLAYING, playback.player.getState());
		}
	}

	@Test
	void pauseHoldsPlayback() throws Exception {
		try (Playback playback = new Playback()) {
			playback.player.play();

			Thread.sleep(800);

			playback.player.pause();

			assertEquals(MediaPlayerState.PAUSED, playback.player.getState());

			// Whatever was in flight when the pause landed may still arrive,
			// so the count is read once things have settled.
			Thread.sleep(200);

			int atPause = playback.frames.get();

			Thread.sleep(700);

			assertEquals(atPause, playback.frames.get(), "frames kept arriving while paused");
		}
	}

	@Test
	void seekMovesPlayback() throws Exception {
		try (Playback playback = new Playback()) {
			// A seek lands on the keyframe at or before the target, so asking
			// for 2.5 s starts playback at the 2 s keyframe, leaving about a
			// second to play out.
			playback.player.seek(2_500_000);
			playback.player.play();

			assertTrue(playback.ended.await(15, TimeUnit.SECONDS), "no end of stream");

			// A second of the asset is fifteen frames, well short of a pass.
			assertTrue(playback.frames.get() < EXPECTED_FRAMES / 2,
					"played more than the tail: " + playback.frames.get());
			assertTrue(playback.frames.get() > 0, "played nothing after the seek");
		}
	}

	@Test
	void needsAtLeastOneSource() throws Exception {
		MediaReader reader = new MediaReader(asset());

		assertThrows(IllegalArgumentException.class,
				() -> new MediaPlayer(reader, null, null));

		reader.close();
	}

	@Test
	void closesTwice() throws Exception {
		Playback playback = new Playback();

		playback.close();
		playback.close();
	}

	@Test
	void adoptedReaderIsClosed() throws Exception {
		MediaReader reader = new MediaReader(asset());

		try (Playback playback = new Playback(reader)) {
			// The player took the reader over, so the Java one is spent and
			// must not be usable any more.
			assertThrows(IllegalStateException.class, reader::getInfo);
		}
	}

	@Test
	void closesFromEndOfStream() throws Exception {
		try (Sources sources = new Sources()) {
			MediaPlayer player = new MediaPlayer(new MediaReader(asset()),
					sources.video, sources.audio);
			CountDownLatch closed = new CountDownLatch(1);

			player.setListener(new MediaPlayerListener() {

				@Override
				public void onEndOfStream() {
					// On the player's own thread, which is the one closing it
					// has to wait for.
					player.close();
					closed.countDown();
				}
			});

			player.seek(2_500_000);
			player.play();

			assertTrue(closed.await(10, TimeUnit.SECONDS), "not closed");
			assertEquals(MediaPlayerState.CLOSED, player.getState());

			player.close();
		}
	}

	@Test
	void closesFromStateChangeOfCommand() throws Exception {
		try (Sources sources = new Sources()) {
			MediaPlayer player = new MediaPlayer(new MediaReader(asset()),
					sources.video, sources.audio);
			AtomicInteger closes = new AtomicInteger();

			player.setListener(new MediaPlayerListener() {

				@Override
				public void onStateChanged(MediaPlayerState state) {
					// Called from within play(), on the calling thread.
					if (state == MediaPlayerState.PLAYING) {
						player.close();
						closes.incrementAndGet();
					}
				}
			});

			player.play();

			assertEquals(1, closes.get());
			assertEquals(MediaPlayerState.CLOSED, player.getState());

			// Commands after the close find nothing to act on.
			player.play();
			player.seek(0);
			player.close();
		}
	}

	private static Path asset() throws Exception {
		URL url = MediaPlayerTest.class.getResource(ASSET);

		assertNotNull(url, "Test asset " + ASSET + " is missing");

		return Paths.get(url.toURI());
	}

	/**
	 * A pair of sources with no tracks, for tests that only need somewhere to
	 * deliver to.
	 */
	private static final class Sources implements AutoCloseable {

		final CustomVideoSource video = new CustomVideoSource();
		final CustomAudioSource audio = new CustomAudioSource();

		@Override
		public void close() {
			video.dispose();
			audio.dispose();
		}
	}

	/**
	 * One playback: the sources, the tracks made from them, the sinks that
	 * count what arrives, and the player feeding it all.
	 */
	private final class Playback implements AutoCloseable {

		final CustomVideoSource videoSource = new CustomVideoSource();
		final CustomAudioSource audioSource = new CustomAudioSource();

		final VideoTrack videoTrack;
		final AudioTrack audioTrack;
		final MediaPlayer player;

		final CountDownLatch ended = new CountDownLatch(1);
		final AtomicInteger frames = new AtomicInteger();
		final AtomicInteger chunks = new AtomicInteger();
		final AtomicInteger width = new AtomicInteger();
		final AtomicInteger height = new AtomicInteger();
		final AtomicInteger sampleRate = new AtomicInteger();
		final AtomicInteger channels = new AtomicInteger();
		final AtomicInteger framesPerChunk = new AtomicInteger();
		final AtomicReference<String> error = new AtomicReference<>();
		final List<Long> frameTimes = Collections.synchronizedList(new ArrayList<>());
		final AtomicLong firstChunkNs = new AtomicLong();

		private final VideoTrackSink videoSink = frame -> {
			frameTimes.add(System.nanoTime());
			frames.incrementAndGet();
			width.set(frame.buffer.getWidth());
			height.set(frame.buffer.getHeight());
		};

		private final AudioTrackSink audioSink = (data, bits, rate, ch, count) -> {
			firstChunkNs.compareAndSet(0, System.nanoTime());
			chunks.incrementAndGet();
			sampleRate.set(rate);
			channels.set(ch);
			framesPerChunk.set(count);
		};

		private boolean closed;


		Playback() throws Exception {
			this(new MediaReader(asset()));
		}

		Playback(MediaReader reader) throws Exception {
			videoTrack = factory.createVideoTrack("video", videoSource);
			audioTrack = factory.createAudioTrack("audio", audioSource);

			videoTrack.addSink(videoSink);
			audioTrack.addSink(audioSink);

			player = new MediaPlayer(reader, videoSource, audioSource);
			player.setListener(new MediaPlayerListener() {

				@Override
				public void onEndOfStream() {
					ended.countDown();
				}

				@Override
				public void onError(String message) {
					error.set(message);
					ended.countDown();
				}
			});
		}

		@Override
		public void close() {
			if (closed) {
				return;
			}

			closed = true;

			player.close();

			videoTrack.removeSink(videoSink);
			audioTrack.removeSink(audioSink);

			videoTrack.dispose();
			audioTrack.dispose();
			videoSource.dispose();
			audioSource.dispose();

			assertNull(error.get());
		}

		private void assertNull(String message) {
			if (message != null) {
				throw new AssertionError("playback reported an error: " + message);
			}
		}
	}

}
