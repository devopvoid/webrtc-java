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

import java.io.IOException;
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
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

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
import org.junit.jupiter.api.io.TempDir;
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

	/**
	 * 320x240 H.264 at 15 fps, ten seconds, in MPEG-TS, with a key frame every
	 * four seconds, at 0.133, 4.133 and 8.133 s: two B-frames put the first
	 * picture 0.133 s in. MPEG-TS has no index, so FFmpeg seeks in it by
	 * searching for a timestamp, which can land past the key frame before the
	 * position. Made with libx264 (veryfast, CRF 42, keyint 60, no scene cuts,
	 * two B-frames) from a moving gradient.
	 */
	private static final String TS_ASSET = "/media-test-h264.ts";

	/** 3.008 seconds in chunks of 10 ms. */
	private static final int EXPECTED_CHUNKS = 300;

	private AudioDeviceModule audioModule;
	private PeerConnectionFactory factory;

	@TempDir
	Path tempDir;


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
			// Half a second is left to play out from 2.5 s.
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
	void seekStartsAtThePositionAskedFor() throws Exception {
		try (Playback playback = new Playback()) {
			// Between the keyframes at 1 s and 2 s. The reader lands on the
			// one at 1 s, and what lies between it and 1.5 s is decoded only
			// to get there.
			playback.player.seek(1_500_000);
			playback.player.play();

			assertTrue(playback.ended.await(15, TimeUnit.SECONDS), "no end of stream");

			// The first frame at or after 1.5 s is the one at 23 / 15 s.
			long first = playback.firstFramePositionUs.get();

			assertTrue(first >= 1_500_000 && first < 1_500_000 + 1_000_000 / 15,
					"first frame at " + first + " us");

			// 1.533 s to 2.933 s, and 1.5 s to 3.008 s of audio. Starting at
			// the keyframe would have played 15 frames and 50 chunks more.
			assertTrue(Math.abs(playback.frames.get() - 22) <= 1,
					"video frames: " + playback.frames.get());
			assertTrue(Math.abs(playback.chunks.get() - 151) <= 2,
					"audio chunks: " + playback.chunks.get());
		}
	}

	@Test
	void fastSeekPlaysFromTheKeyframeBefore() throws Exception {
		try (Playback playback = new Playback()) {
			// Between the keyframes at 1 s and 2 s: playback starts at the one
			// at 1 s, with nothing dropped.
			playback.player.fastSeek(1_500_000);
			playback.player.play();

			assertTrue(playback.ended.await(15, TimeUnit.SECONDS), "no end of stream");

			assertEquals(1_000_000, playback.firstFramePositionUs.get());

			// 1 s to 2.933 s, and the audio from the first packet the reader
			// lands on, a few milliseconds after 1 s, uncut: about half a
			// second more of each than seek gives.
			assertTrue(Math.abs(playback.frames.get() - 30) <= 1,
					"video frames: " + playback.frames.get());
			assertTrue(Math.abs(playback.chunks.get() - 198) <= 3,
					"audio chunks: " + playback.chunks.get());
		}
	}

	@Test
	void seekCutsTheAudioFrameItFallsIn() throws Exception {
		// Two seconds in frames of 100 ms, at the rate WebRTC takes, so that
		// nothing is resampled and every sample played is one of the file's.
		Path flac = TestMedia.constantFlac(tempDir, 48000, 20, (short) 1000);

		try (Playback playback = new Playback(new MediaReader(flac))) {
			// Half way into the frame at 1 s, which the reader lands on.
			playback.player.seek(1_050_000);
			playback.player.play();

			assertTrue(playback.ended.await(15, TimeUnit.SECONDS), "no end of stream");

			// 0.95 s is exactly 95 chunks, none of them padded.
			assertEquals(95, playback.chunks.get());
			assertEquals(95 * 480, playback.nonZeroSamples.get());
		}
	}

	@Test
	void seekWithoutIndexStartsAtThePositionAskedFor() throws Exception {
		try (Playback playback = new Playback(new MediaReader(
				Paths.get(MediaPlayerTest.class.getResource(TS_ASSET).toURI())))) {
			// Just before the key frame at 8.133 s. A search for 7.8 s lands
			// past the one at 4.133 s, and the decoder has nothing to show
			// until 8.133 s, so the player has to look further back.
			playback.player.seek(7_800_000);
			playback.player.play();

			assertTrue(playback.ended.await(15, TimeUnit.SECONDS), "no end of stream");

			// The picture at 7.8 s is the 116th, 0.133 s + 115 / 15 s in.
			long first = playback.firstFramePositionUs.get();

			assertTrue(first >= 7_800_000 && first < 7_800_000 + 1_000_000 / 15,
					"first frame at " + first + " us");

			// 7.8 s to the end: the last 35 of the 150 pictures.
			assertTrue(Math.abs(playback.frames.get() - 35) <= 1,
					"video frames: " + playback.frames.get());
		}
	}

	@Test
	void seekWithoutIndexToItsStartPlaysItAll() throws Exception {
		try (Playback playback = new Playback(new MediaReader(
				Paths.get(MediaPlayerTest.class.getResource(TS_ASSET).toURI())))) {
			// Before the first picture, which is 0.133 s in: there is nothing
			// further back to look for, and the player starts at that picture.
			playback.player.seek(0);
			playback.player.play();

			assertTrue(playback.ended.await(20, TimeUnit.SECONDS), "no end of stream");

			assertEquals(133_333, playback.firstFramePositionUs.get(), 1);
			assertTrue(Math.abs(playback.frames.get() - 150) <= 1,
					"video frames: " + playback.frames.get());
		}
	}

	@Test
	void seekNearTheEndIsNotLost() throws Exception {
		// Two seconds of video only. All of it is decoded and queued within
		// moments, so the decoding thread spends the playback waiting for the
		// queue to play out, which is where a seek used to be mistaken for the
		// end of the source.
		try (Playback playback = new Playback(new MediaReader(
				Paths.get(MediaPlayerTest.class.getResource("/media-test-h264.mkv").toURI())))) {
			playback.player.play();

			Thread.sleep(700);

			int before = playback.frames.get();

			assertTrue(before > 0 && before < 30, "frames before the seek: " + before);
			assertEquals(1, playback.ended.getCount(), "ended before the seek");

			playback.player.seek(0);

			assertTrue(playback.ended.await(15, TimeUnit.SECONDS), "no end of stream");

			// The seek went back to the start, so the whole source plays again
			// on top of what was delivered before it.
			assertTrue(playback.frames.get() >= before + 29,
					"playback did not go on after the seek: " + playback.frames.get()
							+ " frames, " + before + " before it");
			assertEquals(MediaPlayerState.ENDED, playback.player.getState());
		}
	}

	@Test
	void positionFollowsABackwardSeekOfAudio() throws Exception {
		// Audio alone, where nothing but the audio sets the position.
		Path flac = TestMedia.constantFlac(tempDir, 48000, 40, (short) 1000);

		try (Playback playback = new Playback(new MediaReader(flac))) {
			playback.player.play();

			Thread.sleep(1500);

			long before = playback.player.getPositionUs();

			assertTrue(before > 1_000_000, "position before the seek: " + before);

			playback.player.seek(0);

			Thread.sleep(400);

			long after = playback.player.getPositionUs();

			// About 0.4 s into the source again, not held at where it was.
			assertTrue(after < before / 2, "position after seeking back: " + after
					+ " us, " + before + " us before it");
			// And still playing: the source is decoded to its end long before
			// it plays out, and a flush that came after the seek had happened
			// threw away all of it, so that the player ended at once.
			assertEquals(MediaPlayerState.PLAYING, playback.player.getState());
			assertTrue(after > 0, "position after seeking back: " + after + " us");
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
	void reportsWhatTheListenerThrows() throws Exception {
		// What the listener throws on the player's thread goes to that
		// thread's uncaught exception handler, as it would on a Java thread,
		// rather than half to native stderr through ExceptionDescribe.
		CountDownLatch reported = new CountDownLatch(1);
		Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();

		Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
			if (e instanceof IllegalStateException && "listener fails on purpose".equals(e.getMessage())) {
				reported.countDown();
			}
			else if (previous != null) {
				previous.uncaughtException(thread, e);
			}
		});

		try (Sources sources = new Sources()) {
			MediaPlayer player = new MediaPlayer(new MediaReader(asset()),
					sources.video, sources.audio);

			player.setListener(new MediaPlayerListener() {

				@Override
				public void onEndOfStream() {
					throw new IllegalStateException("listener fails on purpose");
				}
			});

			player.seek(2_500_000);
			player.play();

			assertTrue(reported.await(10, TimeUnit.SECONDS), "not reported");

			player.close();
		}
		finally {
			Thread.setDefaultUncaughtExceptionHandler(previous);
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

	@Test
	void closesFromVideoSink() throws Exception {
		try (Sources sources = new Sources()) {
			VideoTrack track = factory.createVideoTrack("video", sources.video);
			MediaPlayer player = new MediaPlayer(new MediaReader(asset()),
					sources.video, sources.audio);
			CountDownLatch closed = new CountDownLatch(1);

			// Called on the player's pacing thread, which is the one closing
			// it has to wait for, and not through the listener.
			VideoTrackSink sink = frame -> {
				try {
					player.close();
					closed.countDown();
				}
				finally {
					frame.release();
				}
			};

			track.addSink(sink);
			player.play();

			assertTrue(closed.await(10, TimeUnit.SECONDS), "not closed");
			assertEquals(MediaPlayerState.CLOSED, player.getState());

			player.close();

			track.removeSink(sink);
			track.dispose();
		}
	}

	@Test
	void closesFromAudioSink() throws Exception {
		try (Sources sources = new Sources()) {
			AudioTrack track = factory.createAudioTrack("audio", sources.audio);
			MediaPlayer player = new MediaPlayer(new MediaReader(asset()),
					sources.video, sources.audio);
			CountDownLatch closed = new CountDownLatch(1);

			AudioTrackSink sink = (data, bits, rate, ch, count) -> {
				player.close();
				closed.countDown();
			};

			track.addSink(sink);
			player.play();

			assertTrue(closed.await(10, TimeUnit.SECONDS), "not closed");
			assertEquals(MediaPlayerState.CLOSED, player.getState());

			player.close();

			track.removeSink(sink);
			track.dispose();
		}
	}

	@Test
	void outlivesDisposedSources() throws Exception {
		CustomVideoSource videoSource = new CustomVideoSource();
		CustomAudioSource audioSource = new CustomAudioSource();
		VideoTrack videoTrack = factory.createVideoTrack("video", videoSource);
		AudioTrack audioTrack = factory.createAudioTrack("audio", audioSource);

		try (MediaPlayer player = new MediaPlayer(new MediaReader(asset()),
				videoSource, audioSource)) {
			player.play();

			Thread.sleep(300);

			// Everything an application holds goes, the player carries on
			// pushing into the sources it was given.
			videoTrack.dispose();
			audioTrack.dispose();
			videoSource.dispose();
			audioSource.dispose();

			Thread.sleep(700);

			assertEquals(MediaPlayerState.PLAYING, player.getState());
		}
	}

	@Test
	void failsWithoutDecoder() throws Exception {
		Path file = TestMedia.muLawWav(tempDir);

		try (Sources sources = new Sources()) {
			// The reader opens, since the demuxer knows the format.
			MediaReader reader = new MediaReader(file);

			assertThrows(IOException.class,
					() -> new MediaPlayer(reader, null, sources.audio));
		}
	}

	@Test
	void followsChannelChange() throws Exception {
		Path file = TestMedia.channelSwitchingFlac(tempDir, 5, 10);

		try (Playback playback = new Playback(new MediaReader(file))) {
			playback.player.play();

			assertTrue(playback.ended.await(10, TimeUnit.SECONDS), "no end of stream");

			// Stereo out throughout, as the file started.
			assertEquals(2, playback.channels.get());
			assertTrue(Math.abs(playback.chunks.get() - 150) <= 2,
					"audio chunks: " + playback.chunks.get());
			assertEquals(0, playback.unevenChunks.get(),
					"chunks with differing channels");
		}
	}

	@Test
	void pausesOnDecodeError() throws Exception {
		Path file = TestMedia.corruptFlac(tempDir, 10, 4);

		try (Playback playback = new Playback(new MediaReader(file))) {
			CountDownLatch failed = new CountDownLatch(1);
			CountDownLatch ended = new CountDownLatch(1);

			playback.player.setListener(new MediaPlayerListener() {

				@Override
				public void onEndOfStream() {
					ended.countDown();
				}

				@Override
				public void onError(String message) {
					failed.countDown();
				}
			});

			playback.player.play();

			assertTrue(failed.await(10, TimeUnit.SECONDS), "no error");

			// Stopped where it failed, rather than claiming to play on.
			assertEquals(MediaPlayerState.PAUSED, playback.player.getState());

			int before = playback.chunks.get();

			// Carries on past the frame that failed.
			playback.player.play();

			assertTrue(ended.await(10, TimeUnit.SECONDS), "no end of stream");
			assertEquals(MediaPlayerState.ENDED, playback.player.getState());
			assertTrue(playback.chunks.get() > before, "nothing played after resuming");
		}
	}

	@Test
	void deliversResamplerTail() throws Exception {
		// One second at 44.1 kHz, which is exactly 48000 samples at 48 kHz.
		Path file = TestMedia.constantFlac(tempDir, 44100, 10, (short) 8000);

		try (Playback playback = new Playback(new MediaReader(file))) {
			playback.player.play();

			assertTrue(playback.ended.await(10, TimeUnit.SECONDS), "no end of stream");

			assertEquals(100, playback.chunks.get());
			assertEquals(48000, playback.nonZeroSamples.get());
		}
	}

	@Test
	void downmixesMoreThanEightChannels() throws Exception {
		// One second of 14 planar channels. A frame has the planes from the
		// ninth on only in its extended data, and reading them from its data
		// pointers instead crashes the process.
		Path file = TestMedia.wideOpusMatroska(tempDir, 14, 50);

		try (Playback playback = new Playback(new MediaReader(file))) {
			playback.player.play();

			assertTrue(playback.ended.await(10, TimeUnit.SECONDS), "no end of stream");

			assertEquals(2, playback.channels.get());
			assertTrue(Math.abs(playback.chunks.get() - 100) <= 2,
					"audio chunks: " + playback.chunks.get());
		}
	}

	@Test
	void fillsAudioGapWithSilence() throws Exception {
		// A second of audio, a second missing, and a second more. Closing the
		// gap up would play the last second a second early, ahead of any
		// video that follows its own timestamps.
		Path file = TestMedia.pcmMatroska(tempDir, (short) 1000,
				blockTimes(0, 10, 2000, 10));

		try (Playback playback = new Playback(new MediaReader(file))) {
			playback.player.play();

			assertTrue(playback.ended.await(10, TimeUnit.SECONDS), "no end of stream");

			assertEquals(300, playback.chunks.get());
			assertEquals(96000, playback.nonZeroSamples.get());
			assertEquals(IntStream.range(100, 200).boxed().collect(Collectors.toList()),
					playback.silentChunks);
		}
	}

	@Test
	void skipsRepeatedAudio() throws Exception {
		// The second second starts 50 ms before the first one ends, so 50 ms
		// of it repeat what has played already.
		Path file = TestMedia.pcmMatroska(tempDir, (short) 1000,
				blockTimes(0, 10, 950, 10));

		try (Playback playback = new Playback(new MediaReader(file))) {
			playback.player.play();

			assertTrue(playback.ended.await(10, TimeUnit.SECONDS), "no end of stream");

			assertEquals(195, playback.chunks.get());
			assertEquals(195 * 480, playback.nonZeroSamples.get());
			assertTrue(playback.silentChunks.isEmpty(),
					"silent chunks: " + playback.silentChunks);
		}
	}

	/**
	 * The times of two runs of 100 ms blocks, in milliseconds.
	 */
	private static long[] blockTimes(long firstStart, int firstBlocks,
			long secondStart, int secondBlocks) {
		return LongStream.concat(
				LongStream.range(0, firstBlocks).map(i -> firstStart + i * 100),
				LongStream.range(0, secondBlocks).map(i -> secondStart + i * 100))
				.toArray();
	}

	private static int countNonZero(byte[] data) {
		int count = 0;

		for (int i = 0; i + 1 < data.length; i += 2) {
			if (data[i] != 0 || data[i + 1] != 0) {
				count++;
			}
		}

		return count;
	}

	private static boolean channelsEqual(byte[] data) {
		// Interleaved 16-bit frames: the left and right sample of a frame are
		// the two bytes pairs of each four.
		for (int i = 0; i + 3 < data.length; i += 4) {
			if (data[i] != data[i + 2] || data[i + 1] != data[i + 3]) {
				return false;
			}
		}

		return true;
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
		final AtomicLong firstFramePositionUs = new AtomicLong(-1);
		final AtomicLong firstChunkNs = new AtomicLong();
		final AtomicInteger unevenChunks = new AtomicInteger();
		final AtomicLong nonZeroSamples = new AtomicLong();
		final List<Integer> silentChunks = Collections.synchronizedList(new ArrayList<>());

		private final VideoTrackSink videoSink = frame -> {
			// The pacer delivers on its own thread, having just moved the
			// position to the frame's presentation time.
			if (firstFramePositionUs.get() < 0) {
				firstFramePositionUs.set(Playback.this.player.getPositionUs());
			}

			frameTimes.add(System.nanoTime());
			frames.incrementAndGet();
			width.set(frame.buffer.getWidth());
			height.set(frame.buffer.getHeight());
		};

		private final AudioTrackSink audioSink = (data, bits, rate, ch, count) -> {
			firstChunkNs.compareAndSet(0, System.nanoTime());
			int index = chunks.getAndIncrement();
			sampleRate.set(rate);
			channels.set(ch);
			framesPerChunk.set(count);

			if (ch == 2 && !channelsEqual(data)) {
				unevenChunks.incrementAndGet();
			}

			int nonZero = countNonZero(data);

			if (nonZero == 0) {
				silentChunks.add(index);
			}

			nonZeroSamples.addAndGet(nonZero);
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
