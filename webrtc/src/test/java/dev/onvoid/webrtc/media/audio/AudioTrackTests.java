/*
 * Copyright 2019 Alex Andres
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

package dev.onvoid.webrtc.media.audio;

import static org.junit.jupiter.api.Assertions.*;

import dev.onvoid.webrtc.TestBase;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.MediaStreamTrackEndedListener;
import dev.onvoid.webrtc.media.MediaStreamTrackMuteListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AudioTrackTests extends TestBase {

	private AudioTrack audioTrack;


	@BeforeEach
	void init() {
		AudioOptions audioOptions = new AudioOptions();
		AudioTrackSource audioSource = factory.createAudioSource(audioOptions);

		audioTrack = factory.createAudioTrack("audioTrack", audioSource);
	}

	@AfterEach
	void dispose() {
		audioTrack.dispose();
	}

	@Test
	void disableEnableTrack() {
		audioTrack.setEnabled(false);

		assertFalse(audioTrack.isEnabled());

		audioTrack.setEnabled(true);

		assertTrue(audioTrack.isEnabled());
	}

	@Test
	void addNullSink() {
		assertThrows(NullPointerException.class, () -> audioTrack.addSink(null));
	}

	@Test
	void removeNullSink() {
		assertThrows(NullPointerException.class, () -> audioTrack.removeSink(null));
	}

	@Test
	void addRemoveSink() {
		AudioTrackSink sink = (data, bitsPerSample, sampleRate, channels, frames) -> { };

		audioTrack.addSink(sink);
		audioTrack.removeSink(sink);
	}

	@Test
	void addRemoveListeners() {
		MediaStreamTrackMuteListener muteListener = (track, muted) -> { };
		MediaStreamTrackEndedListener endedListener = track -> { };

		audioTrack.addTrackMuteListener(muteListener);
		audioTrack.addTrackEndedListener(endedListener);

		audioTrack.removeTrackMuteListener(muteListener);
		audioTrack.removeTrackEndedListener(endedListener);
	}

	@Test
	void addRemoveListenersConcurrently() throws Exception {
		// Each thread has a track of its own and new listeners (a lambda without
		// captures would be one shared instance), so that the only state the
		// threads share is the native registry of Java listeners.
		final int threadCount = 8;
		List<AudioTrack> tracks = new ArrayList<>();

		for (int i = 0; i < threadCount; i++) {
			AudioTrackSource source = factory.createAudioSource(new AudioOptions());
			tracks.add(factory.createAudioTrack("audioTrack" + i, source));
		}

		CountDownLatch start = new CountDownLatch(1);
		List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
		List<Thread> threads = new ArrayList<>();

		for (AudioTrack track : tracks) {
			Thread thread = new Thread(() -> {
				try {
					start.await();

					for (int i = 0; i < 200; i++) {
						MediaStreamTrackEndedListener listener = new MediaStreamTrackEndedListener() {

							@Override
							public void onTrackEnd(MediaStreamTrack t) {
							}
						};

						track.addTrackEndedListener(listener);
						track.removeTrackEndedListener(listener);
					}
				}
				catch (Throwable e) {
					errors.add(e);
				}
			});
			thread.start();
			threads.add(thread);
		}

		start.countDown();

		for (Thread thread : threads) {
			thread.join();
		}
		for (AudioTrack track : tracks) {
			track.dispose();
		}

		assertTrue(errors.isEmpty(), () -> "Errors: " + errors);
	}

}
