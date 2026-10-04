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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;
import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.I420Buffer;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Tests decoding video in hardware: that a player asked to decode in hardware
 * sends the very pictures software decoding does, and that it falls back to
 * software where the hardware has nothing to offer.
 * <p>
 * A machine without a hardware decoder for the codec skips the tests that need
 * one, as CI runners do. Set the system property {@code
 * webrtc.test.hardwareDecoding} to {@code true} on a machine that has one, to
 * make those tests fail instead.
 * <p>
 * The assets are 320x240 at 15 fps, two seconds, with a key frame every second:
 * {@code media-test-h264.mkv} is H.264 High made by the VideoToolbox encoder,
 * {@code media-test-vp9.webm} is VP9 made by libvpx, and {@code
 * media-test-vp9-444.webm} is the same in profile 1, 4:4:4. All are a moving
 * gradient with noise, wrapped in Matroska by a script, and have no audio.
 *
 * @author Alex Andres
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class HardwareDecodingTest {

	private static final boolean HARDWARE_REQUIRED = Boolean.getBoolean("webrtc.test.hardwareDecoding");

	private static final String H264 = "/media-test-h264.mkv";

	private static final String VP9 = "/media-test-vp9.webm";

	/** VP9 profile 1, 4:4:4, which no hardware decoder takes. */
	private static final String VP9_444 = "/media-test-vp9-444.webm";

	/** VP8, for which no platform has a decoder this module uses. */
	private static final String VP8 = "/media-test.webm";

	private static final int FRAMES = 30;

	private static final int TIMEOUT_SECONDS = 15;

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
	void h264MatchesSoftware() throws Exception {
		assertMatchesSoftware(H264);
	}

	@Test
	void vp9MatchesSoftware() throws Exception {
		assertMatchesSoftware(VP9);
	}

	@Test
	void softwareIsTheDefault() throws Exception {
		Result result = play(H264, false, 0);

		assertFalse(result.hardware);
		assertNull(result.error);
		assertEquals(FRAMES, result.frames.size());
	}

	@Test
	void codecWithoutHardwareFallsBack() throws Exception {
		// No platform decoder for VP8: the player opens in software and says so.
		Result result = play(VP8, true, 0);

		assertFalse(result.hardware);
		assertNull(result.error);
		assertTrue(result.frames.size() > 0, "no frames");
	}

	@Test
	void streamTheHardwareDoesNotTakeFallsBack() throws Exception {
		Result software = play(VP9_444, false, 0);
		Result hardware = play(VP9_444, true, 0);

		assertNull(hardware.error);

		// Asked for hardware, it plays in software, with the same pictures.
		assertFalse(hardware.hardware);
		assertEquals(FRAMES, hardware.frames.size());
		assertEquals(software.frames, hardware.frames);
	}

	@Test
	void fileSourceTakesTheOption() throws Exception {
		try (MediaFileSource source = new MediaFileSource(path(H264), true)) {
			// Opened and not yet played: the decoder is set up for hardware,
			// which the first frame then confirms.
			requireHardware(source.getPlayer().isHardwareDecoding());
		}

		try (MediaFileSource source = new MediaFileSource(path(H264))) {
			assertFalse(source.getPlayer().isHardwareDecoding());
		}
	}

	@Test
	void seeksInHardware() throws Exception {
		Result software = play(H264, false, 1_000_000);
		Result hardware = play(H264, true, 1_000_000);

		requireHardware(hardware);

		// Half the media from the second key frame: the decoder was flushed
		// by the seek, and went on in hardware.
		assertEquals(software.frames, hardware.frames);
		assertTrue(hardware.frames.size() < FRAMES, "frames: " + hardware.frames.size());
	}

	private void assertMatchesSoftware(String asset) throws Exception {
		Result software = play(asset, false, 0);
		Result hardware = play(asset, true, 0);

		assertNull(software.error);
		assertNull(hardware.error);

		requireHardware(hardware);

		assertEquals(FRAMES, software.frames.size());

		// The pictures are the same, frame by frame.
		assertEquals(software.frames, hardware.frames);
	}

	/**
	 * Fails where hardware is required and the player did not use it, and
	 * skips the test where it is not required.
	 */
	private static void requireHardware(Result result) {
		requireHardware(result.hardware);
	}

	private static void requireHardware(boolean hardware) {
		if (HARDWARE_REQUIRED) {
			assertTrue(hardware, "the player did not decode in hardware");
		}
		else {
			assumeTrue(hardware, "no hardware decoder");
		}
	}

	/**
	 * Plays the asset from the given position to its end, in real time, and
	 * returns what a sink on the track saw: a checksum of the luma of each
	 * frame.
	 */
	private Result play(String asset, boolean hardware, long positionUs) throws Exception {
		Result result = new Result();
		CountDownLatch ended = new CountDownLatch(1);

		CustomVideoSource source = new CustomVideoSource();
		VideoTrack track = factory.createVideoTrack("video", source);
		VideoTrackSink sink = frame -> {
			result.frames.add(lumaChecksum(frame.buffer.toI420()));

			frame.release();
		};

		track.addSink(sink);

		try (MediaPlayer player = new MediaPlayer(new MediaReader(path(asset)), source, null, hardware)) {
			player.setListener(new MediaPlayerListener() {

				@Override
				public void onEndOfStream() {
					ended.countDown();
				}

				@Override
				public void onError(String message) {
					result.error = message;
					ended.countDown();
				}
			});

			if (positionUs > 0) {
				player.seek(positionUs);
			}

			player.play();

			assertTrue(ended.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "no end of stream");

			result.hardware = player.isHardwareDecoding();
		}
		finally {
			track.removeSink(sink);
			track.dispose();
			source.dispose();
		}

		return result;
	}

	/** A checksum of the luma of a picture, row by row so that the stride does not matter. */
	private static long lumaChecksum(I420Buffer buffer) {
		ByteBuffer luma = buffer.getDataY();
		byte[] row = new byte[buffer.getWidth()];
		CRC32 crc = new CRC32();

		for (int y = 0; y < buffer.getHeight(); y++) {
			ByteBuffer line = luma.duplicate();
			line.position(y * buffer.getStrideY());
			line.get(row);

			crc.update(row, 0, row.length);
		}

		return crc.getValue();
	}

	private static Path path(String asset) throws Exception {
		return Paths.get(HardwareDecodingTest.class.getResource(asset).toURI());
	}

	private static final class Result {

		final List<Long> frames = Collections.synchronizedList(new ArrayList<>());
		volatile String error;
		volatile boolean hardware;

	}

}
