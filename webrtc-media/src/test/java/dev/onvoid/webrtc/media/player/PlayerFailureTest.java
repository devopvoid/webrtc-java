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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.onvoid.webrtc.media.audio.CustomAudioSource;
import dev.onvoid.webrtc.media.video.CustomVideoSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/** Failures on native threads must be observable without ending the JVM. */
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(20)
class PlayerFailureTest {

	@AfterEach
	void disarm() {
		AllocationFault.disarm();
	}

	@Test
	void decoderExceptionStopsAndCanBeClosed() throws Exception {
		try (Playback playback = new Playback()) {
			AllocationFault.failDecoderAllocation();
			playback.player.play();
			assertTrue(playback.failed.await(10, TimeUnit.SECONDS));
			assertEquals(MediaPlayerState.PAUSED, playback.player.getState());
			assertEquals("Decoder thread failed", playback.errors.get(0));
			playback.player.play();
			playback.player.seek(1000);
			assertEquals(MediaPlayerState.PAUSED, playback.player.getState());
		}
	}

	@Test
	void reportingOutputFailureCanRunOutOfMemory() throws Exception {
		try (Playback playback = new Playback()) {
			AllocationFault.failNextAllocation();
			AllocationFault.failOutput(true, 1);
			playback.player.play();
			assertTrue(playback.failed.await(10, TimeUnit.SECONDS));
			assertEquals(MediaPlayerState.PAUSED, playback.player.getState());
			assertEquals("Reporting output failure failed", playback.errors.get(0));
		}
	}

	@Test
	void oneRejectedVideoFrameDoesNotStopPlayback() throws Exception {
		assertTransient(true);
	}

	@Test
	void oneRejectedAudioChunkDoesNotStopPlayback() throws Exception {
		assertTransient(false);
	}

	@Test
	void persistentVideoFailureStopsPlayback() throws Exception {
		assertPersistent(true);
	}

	@Test
	void persistentAudioFailureStopsPlayback() throws Exception {
		assertPersistent(false);
	}

	@Test
	void failedOutputsDoNotAdvancePosition() throws Exception {
		try (Playback playback = new Playback()) {
			AllocationFault.failOutput(true, -1);
			AllocationFault.failOutput(false, -1);
			playback.player.play();
			assertTrue(playback.failed.await(10, TimeUnit.SECONDS));
			assertEquals(0, playback.player.getPositionUs());
			assertEquals(MediaPlayerState.PAUSED, playback.player.getState());
		}
	}

	private void assertTransient(boolean video) throws Exception {
		try (Playback playback = new Playback()) {
			AllocationFault.failOutput(video, 1);
			playback.player.play();
			assertTrue(playback.ended.await(10, TimeUnit.SECONDS));
			assertEquals(MediaPlayerState.ENDED, playback.player.getState());
			assertEquals(1, playback.errors.size());
			assertTrue(playback.errors.get(0).startsWith(video ? "Video output dropped:" : "Audio output dropped:"));
			assertFalse(AllocationFault.isArmed());
		}
	}

	private void assertPersistent(boolean video) throws Exception {
		try (Playback playback = new Playback()) {
			AllocationFault.failOutput(video, -1);
			long started = System.nanoTime();
			playback.player.play();
			assertTrue(playback.failed.await(10, TimeUnit.SECONDS));
			assertEquals(MediaPlayerState.PAUSED, playback.player.getState());
			// One initial drop report, at most one more per second, and a
			// terminal report even when the drop reports are rate limited.
			long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);
			assertTrue(playback.errors.size() >= 2);
			assertTrue(playback.errors.size() <= 2 + seconds);
			assertEquals(video ? "Persistent video output failure" : "Persistent audio output failure",
					playback.errors.get(playback.errors.size() - 1));
			assertEquals(0, playback.player.getQueuedVideoBytes());
			assertEquals(1, playback.ended.getCount());
			playback.player.play();
			assertEquals(MediaPlayerState.PAUSED, playback.player.getState());
		}
	}

	private static final class Playback implements AutoCloseable {
		final CustomVideoSource video = new CustomVideoSource();
		final CustomAudioSource audio = new CustomAudioSource();
		final MediaPlayer player;
		final List<String> errors = new CopyOnWriteArrayList<>();
		final CountDownLatch ended = new CountDownLatch(1);
		final CountDownLatch failed = new CountDownLatch(1);

		Playback() throws Exception {
			player = new MediaPlayer(new MediaReader(MediaReaderTest.asset("/media-test.webm")), video, audio);
			player.setListener(new MediaPlayerListener() {
				@Override
				public void onError(String message) {
					errors.add(message);
					if (!message.contains("output dropped:")) {
						failed.countDown();
					}
				}

				@Override
				public void onEndOfStream() {
					ended.countDown();
				}
			});
		}

		@Override
		public void close() {
			player.close();
			video.dispose();
			audio.dispose();
		}
	}
}
