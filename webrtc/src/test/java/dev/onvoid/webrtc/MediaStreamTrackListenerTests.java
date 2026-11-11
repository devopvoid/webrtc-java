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

package dev.onvoid.webrtc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.onvoid.webrtc.internal.AllocationFault;
import dev.onvoid.webrtc.media.MediaStreamTrackEndedListener;
import dev.onvoid.webrtc.media.MediaStreamTrackMuteListener;
import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * A track keeps a bare pointer to the observer of each listener, so a listener
 * that fails to be registered must leave the track before its observer goes.
 * With an observer left behind, the first change of the track calls into freed
 * memory, which ends the test run.
 */
@Execution(ExecutionMode.SAME_THREAD)
class MediaStreamTrackListenerTests extends TestBase {

	private AudioTrackSource audioSource;

	private AudioTrack audioTrack;


	@BeforeEach
	void init() {
		audioSource = factory.createAudioSource(new AudioOptions());
		audioTrack = factory.createAudioTrack("audioTrack", audioSource);
	}

	@AfterEach
	void dispose() {
		AllocationFault.disarm();

		audioTrack.dispose();
		audioSource.dispose();
	}

	@Test
	void failedMuteListenerLeavesTrack() {
		AtomicInteger calls = new AtomicInteger();
		MediaStreamTrackMuteListener listener = (track, muted) -> calls.incrementAndGet();

		AllocationFault.failNextListener();

		assertThrows(Error.class, () -> audioTrack.addTrackMuteListener(listener));

		audioTrack.setEnabled(false);

		assertEquals(0, calls.get());

		// Nothing of the failed registration is in the way of a new one.
		audioTrack.addTrackMuteListener(listener);
		audioTrack.setEnabled(true);

		assertEquals(1, calls.get());

		audioTrack.removeTrackMuteListener(listener);
		audioTrack.setEnabled(false);

		assertEquals(1, calls.get());
	}

	@Test
	void failedEndedListenerLeavesTrack() {
		MediaStreamTrackEndedListener listener = track -> { };

		AllocationFault.failNextListener();

		assertThrows(Error.class, () -> audioTrack.addTrackEndedListener(listener));

		// Any change of the track reaches all of its observers.
		audioTrack.setEnabled(false);

		audioTrack.addTrackEndedListener(listener);
		audioTrack.setEnabled(true);
		audioTrack.removeTrackEndedListener(listener);
	}

}
