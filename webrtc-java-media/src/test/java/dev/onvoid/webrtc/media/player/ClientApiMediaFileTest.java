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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import dev.onvoid.webrtc.client.AudioMode;
import dev.onvoid.webrtc.client.Peer;
import dev.onvoid.webrtc.client.PeerOptions;
import dev.onvoid.webrtc.client.Signaling;
import dev.onvoid.webrtc.client.SignalingPair;
import dev.onvoid.webrtc.client.WebRtc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Sends a media file through tracks of the client API, as its guide shows.
 *
 * @author Alex Andres
 */
@Execution(ExecutionMode.SAME_THREAD)
class ClientApiMediaFileTest {

	private static final String ASSET = "/media-test.webm";

	@Test
	void sendsFileToPeer() throws Exception {
		List<Throwable> errors = new CopyOnWriteArrayList<>();
		AtomicInteger frames = new AtomicInteger();
		AtomicInteger chunks = new AtomicInteger();
		CountDownLatch received = new CountDownLatch(2);

		// The runtime comes first, since it loads the native library the file's
		// sources need. The file's audio is pushed audio, hence no microphone.
		WebRtc rtc = WebRtc.builder().audio(AudioMode.HEADLESS).errorHandler(errors::add).build();

		try (MediaFileSource file = new MediaFileSource(asset())) {
			try {
				SignalingPair link = Signaling.loopback();
				Peer sender = rtc.createPeer(PeerOptions.builder().signaling(link.first()).build());
				Peer receiver = rtc.createPeer(PeerOptions.builder().signaling(link.second()).polite(true).build());

				receiver.onTrack(track -> {
					if (track.isVideo()) {
						track.asVideo().onFrame(frame -> {
							if (frames.incrementAndGet() == 10) {
								received.countDown();
							}
						});
					}
					else {
						track.asAudio().onAudio(chunk -> {
							if (chunks.incrementAndGet() == 50) {
								received.countDown();
							}
						});
					}
				});

				sender.addTrack(rtc.media().videoTrack("movie", file.getVideoSource()));
				sender.addTrack(rtc.media().audioTrack("movie", file.getAudioSource()));
				file.play();

				assertTrue(received.await(15, TimeUnit.SECONDS),
						"video frames: " + frames.get() + ", audio chunks: " + chunks.get());
			}
			finally {
				// The runtime and its tracks close before the file and its sources.
				rtc.close();
			}
		}

		assertEquals(0, errors.size(), () -> "Errors: " + errors);
	}

	private static Path asset() throws Exception {
		URL url = ClientApiMediaFileTest.class.getResource(ASSET);

		assertNotNull(url, "Test asset " + ASSET + " is missing");

		return Paths.get(url.toURI());
	}

}
