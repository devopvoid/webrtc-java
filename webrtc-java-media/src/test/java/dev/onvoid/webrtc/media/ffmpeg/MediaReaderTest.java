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

package dev.onvoid.webrtc.media.ffmpeg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

/**
 * Tests reading a media source. The asset is three seconds of VP8 and Opus in
 * WebM, which is what a peer connection would be fed with.
 *
 * @author Alex Andres
 */
class MediaReaderTest {

	/** The committed test asset: 320x240 VP8 at 15 fps, 48 kHz mono Opus. */
	private static final String ASSET = "/media-test.webm";

	/**
	 * The first quarter second of Big Buck Bunny (CC BY 3.0, Blender
	 * Foundation) as the 1080p AVI it is distributed in, cut without
	 * re-encoding: MS-MPEG4 v2 video at 24 fps, 48 kHz stereo MP3.
	 */
	static final String AVI_ASSET = "/media-test.avi";

	/**
	 * Three seconds of 160x120 MJPEG at 25 fps and 8 kHz mono PCM in
	 * Matroska, with all of the audio stored ahead of all of the video.
	 */
	static final String COARSE_ASSET = "/media-test-coarse.mkv";


	@Test
	void readsVideoInfo() throws Exception {
		try (MediaReader reader = new MediaReader(asset())) {
			MediaInfo info = reader.getInfo();

			assertTrue(info.hasVideo());
			assertEquals(320, info.getVideoWidth());
			assertEquals(240, info.getVideoHeight());
			assertEquals(15.0, info.getFrameRate(), 0.01);
			assertEquals("vp8", info.getVideoCodec());
		}
	}

	@Test
	void readsAudioInfo() throws Exception {
		try (MediaReader reader = new MediaReader(asset())) {
			MediaInfo info = reader.getInfo();

			assertTrue(info.hasAudio());
			assertEquals(48000, info.getSampleRate());
			assertEquals(1, info.getChannels());
			assertEquals("opus", info.getAudioCodec());
		}
	}

	@Test
	void readsDuration() throws Exception {
		try (MediaReader reader = new MediaReader(asset())) {
			// The asset runs 3.008 seconds. The tolerance is there because the
			// duration comes from the container, and FFmpeg may be replaced.
			assertEquals(3_008_000, reader.getInfo().getDurationUs(), 50_000);
		}
	}

	@Test
	void readsAviInfo() throws Exception {
		try (MediaReader reader = new MediaReader(asset(AVI_ASSET))) {
			MediaInfo info = reader.getInfo();

			assertEquals(1920, info.getVideoWidth());
			assertEquals(1080, info.getVideoHeight());
			assertEquals(24.0, info.getFrameRate(), 0.01);
			assertEquals("msmpeg4v2", info.getVideoCodec());

			assertEquals(48000, info.getSampleRate());
			assertEquals(2, info.getChannels());
			assertEquals("mp3", info.getAudioCodec());
		}
	}

	@Test
	void missingSourceFails() {
		IOException e = assertThrows(IOException.class,
				() -> new MediaReader(Paths.get("no-such-file.webm")));

		// The message has to name the source and what FFmpeg objected to,
		// otherwise a wrong path is a guessing game.
		assertTrue(e.getMessage().contains("no-such-file.webm"), e.getMessage());
	}

	@Test
	void closesTwice() throws Exception {
		MediaReader reader = new MediaReader(asset());

		reader.close();
		reader.close();
	}

	@Test
	void infoAfterCloseFails() throws Exception {
		MediaReader reader = new MediaReader(asset());

		reader.close();

		assertThrows(IllegalStateException.class, reader::getInfo);
	}

	private static Path asset() throws Exception {
		return asset(ASSET);
	}

	static Path asset(String name) throws Exception {
		URL url = MediaReaderTest.class.getResource(name);

		assertNotNull(url, "Test asset " + name + " is missing");

		return Paths.get(url.toURI());
	}

}
