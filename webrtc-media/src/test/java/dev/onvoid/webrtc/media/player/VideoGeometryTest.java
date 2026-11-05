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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;
import dev.onvoid.webrtc.media.video.I420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;
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
 * Tests that a video is shown the way its file says, whatever the shape in
 * which the pictures are stored: turned by a display matrix, and with pixels
 * that are not square.
 *
 * @author Alex Andres
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class VideoGeometryTest {

	/** 320x240 H.264 in yuv420p, whose frames are handed on as they are. */
	private static final String H264 = "/media-test-h264.mkv";

	/** 320x240 H.264 in yuv444p, whose frames are converted. */
	private static final String H264_444 = "/media-test-h264-444.mkv";

	/** 160x120 MJPEG in JFIF pictures. */
	private static final String MJPEG = "/media-test-coarse.mkv";

	private AudioDeviceModule audioModule;
	private PeerConnectionFactory factory;

	@TempDir
	Path directory;


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
	void plainVideoStaysAsItIs() throws Exception {
		assertPlays(path(H264), false, 320, 240, 0, 320, 240);
	}

	@Test
	void quarterTurnsReachTheSink() throws Exception {
		Path source = path(H264);

		assertPlays(TestMedia.rotatedMatroska(directory, source, 90), false, 320, 240, 90, 240, 320);
		assertPlays(TestMedia.rotatedMatroska(directory, source, 180), false, 320, 240, 180, 320, 240);
		assertPlays(TestMedia.rotatedMatroska(directory, source, 270), false, 320, 240, 270, 240, 320);
	}

	@Test
	void convertedFramesKeepTheirRotation() throws Exception {
		Path file = TestMedia.rotatedMatroska(directory, path(H264_444), 90);

		assertPlays(file, false, 320, 240, 90, 240, 320);
	}

	@Test
	void otherAnglesPlayAsStored() throws Exception {
		Path file = TestMedia.rotatedMatroska(directory, path(H264), 45);

		assertPlays(file, false, 320, 240, 0, 320, 240);
	}

	// A mirror image is a half turn to the matrix. Read as one, it would play
	// on its head.
	@Test
	void mirroredVideoIsNotTurned() throws Exception {
		Path file = TestMedia.mirroredMatroska(directory, path(H264));

		assertPlays(file, false, 320, 240, 0, 320, 240);
	}

	// H.264 replaces the ratio of the container with that of its own headers,
	// which here say nothing, so the frames do not have it.
	@Test
	void containerRatioCounts() throws Exception {
		// 16:15, as in PAL, but for a picture of 320x240.
		Path file = TestMedia.displaySizeMatroska(directory, path(H264), 1024, 720);

		assertPlays(file, false, 342, 240, 0, 342, 240);
	}

	@Test
	void narrowPixelsGrowTheHeight() throws Exception {
		// 4:5.
		Path file = TestMedia.displaySizeMatroska(directory, path(H264), 320, 300);

		assertPlays(file, false, 320, 300, 0, 320, 300);
	}

	// Matroska reports 1:1 for a track without a display size, which is not to
	// hide what the pictures say.
	@Test
	void pictureRatioCounts() throws Exception {
		Path file = TestMedia.pixelDensityMatroska(directory, path(MJPEG), 2, 1);

		assertPlays(file, false, 320, 120, 0, 320, 120);
	}

	@Test
	void convertedFramesAreScaled() throws Exception {
		Path file = TestMedia.displaySizeMatroska(directory, path(H264_444), 640, 240);

		assertPlays(file, false, 640, 240, 0, 640, 240);
	}

	@Test
	void brokenRatioIsIgnored() throws Exception {
		Path file = TestMedia.displaySizeMatroska(directory, path(H264), 32000, 1);

		assertPlays(file, false, 320, 240, 0, 320, 240);
	}

	// Without hardware this is decoded in software, which is as good a result.
	@Test
	void hardwareDecodingKeepsTheShape() throws Exception {
		Path source = path(H264);

		assertPlays(TestMedia.rotatedMatroska(directory, source, 90), true, 320, 240, 90, 240, 320);
		assertPlays(TestMedia.displaySizeMatroska(directory, source, 640, 240), true, 640, 240, 0, 640, 240);
	}

	@Test
	void scalingKeepsThePicture() throws Exception {
		Picture plain = play(path(H264), false);
		Picture wide = play(TestMedia.displaySizeMatroska(directory, path(H264), 640, 240), false);

		int blocks = 8;

		for (int y = 0; y < blocks; y++) {
			for (int x = 0; x < blocks; x++) {
				double expected = plain.meanLuma(x, y, blocks);
				double actual = wide.meanLuma(x, y, blocks);

				assertTrue(Math.abs(expected - actual) < 8,
						"block " + x + "," + y + ": " + expected + " became " + actual);
			}
		}
	}

	private void assertPlays(Path file, boolean hardware, int width, int height, int rotation,
			int shownWidth, int shownHeight) throws Exception {
		Picture picture = play(file, hardware);

		assertEquals(width, picture.width, "frame width");
		assertEquals(height, picture.height, "frame height");
		assertEquals(rotation, picture.rotation, "frame rotation");
		assertEquals(shownWidth, picture.shownWidth, "reported width");
		assertEquals(shownHeight, picture.shownHeight, "reported height");
	}

	/** Plays a file up to its first picture, as a sink on its track sees it. */
	private Picture play(Path file, boolean hardware) throws Exception {
		CompletableFuture<VideoFrameCopy> first = new CompletableFuture<>();

		try (MediaFileSource source = new MediaFileSource(file, hardware)) {
			VideoTrack track = factory.createVideoTrack("video", source.getVideoSource());
			VideoTrackSink sink = frame -> {
				if (!first.isDone()) {
					first.complete(new VideoFrameCopy(frame));
				}
			};

			track.addSink(sink);

			source.play();

			VideoFrameCopy frame = first.get(15, TimeUnit.SECONDS);

			track.removeSink(sink);
			track.dispose();

			return new Picture(frame, source.getInfo());
		}
	}

	private static Path path(String asset) throws Exception {
		return Paths.get(VideoGeometryTest.class.getResource(asset).toURI());
	}

	/** What outlives the frame a sink is given. */
	private static final class VideoFrameCopy {

		final int width;
		final int height;
		final int rotation;
		final byte[] luma;


		VideoFrameCopy(VideoFrame frame) {
			I420Buffer buffer = frame.buffer.toI420();

			width = buffer.getWidth();
			height = buffer.getHeight();
			rotation = frame.rotation;
			luma = new byte[width * height];

			ByteBuffer plane = buffer.getDataY();

			for (int y = 0; y < height; y++) {
				ByteBuffer line = plane.duplicate();

				line.position(y * buffer.getStrideY());
				line.get(luma, y * width, width);
			}

			buffer.release();
		}
	}

	private static final class Picture {

		final int width;
		final int height;
		final int rotation;
		final int shownWidth;
		final int shownHeight;
		final byte[] luma;


		Picture(VideoFrameCopy frame, MediaInfo info) {
			width = frame.width;
			height = frame.height;
			rotation = frame.rotation;
			luma = frame.luma;
			shownWidth = info.getVideoWidth();
			shownHeight = info.getVideoHeight();
		}

		/** The mean luma of one block of a picture cut into equal blocks. */
		double meanLuma(int blockX, int blockY, int blocks) {
			int left = width * blockX / blocks;
			int right = width * (blockX + 1) / blocks;
			int top = height * blockY / blocks;
			int bottom = height * (blockY + 1) / blocks;
			long sum = 0;

			for (int y = top; y < bottom; y++) {
				for (int x = left; x < right; x++) {
					sum += luma[y * width + x] & 0xFF;
				}
			}

			return (double) sum / ((right - left) * (bottom - top));
		}
	}
}
