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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSink;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Tests the turnkey way to send a media file, which is the thing the issue
 * behind this module actually asked for.
 *
 * @author Alex Andres
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class MediaFileSourceTest {

	private static final String ASSET = "/media-test.webm";

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
	void exposesWhatTheFileHas() throws Exception {
		try (MediaFileSource source = new MediaFileSource(asset())) {
			assertTrue(source.getInfo().hasVideo());
			assertTrue(source.getInfo().hasAudio());

			assertNotNull(source.getVideoSource());
			assertNotNull(source.getAudioSource());

			assertEquals(MediaPlayerState.IDLE, source.getState());
		}
	}

	@Test
	void sendsTheFileThroughTracks() throws Exception {
		AtomicInteger frames = new AtomicInteger();
		AtomicInteger chunks = new AtomicInteger();
		CountDownLatch ended = new CountDownLatch(1);

		try (MediaFileSource source = new MediaFileSource(asset())) {
			VideoTrack videoTrack = factory.createVideoTrack("video", source.getVideoSource());
			AudioTrack audioTrack = factory.createAudioTrack("audio", source.getAudioSource());

			VideoTrackSink videoSink = frame -> frames.incrementAndGet();
			AudioTrackSink audioSink = (data, bits, rate, ch, count) -> chunks.incrementAndGet();

			videoTrack.addSink(videoSink);
			audioTrack.addSink(audioSink);

			source.setListener(new MediaPlayerListener() {

				@Override
				public void onEndOfStream() {
					ended.countDown();
				}
			});

			source.play();

			assertTrue(ended.await(15, TimeUnit.SECONDS), "no end of stream");

			assertTrue(frames.get() > 40, "video frames: " + frames.get());
			assertTrue(chunks.get() > 290, "audio chunks: " + chunks.get());

			videoTrack.removeSink(videoSink);
			audioTrack.removeSink(audioSink);

			// The tracks go before the source, which disposes of what they
			// were made from.
			videoTrack.dispose();
			audioTrack.dispose();
		}
	}

	@Test
	void missingFileFails() {
		assertThrows(IOException.class, () -> new MediaFileSource(Paths.get("no-such-file.webm")));
	}

	@Test
	void closesTwice() throws Exception {
		MediaFileSource source = new MediaFileSource(asset());

		source.close();
		source.close();
	}

	private static Path asset() throws Exception {
		URL url = MediaFileSourceTest.class.getResource(ASSET);

		assertNotNull(url, "Test asset " + ASSET + " is missing");

		return Paths.get(url.toURI());
	}

}
