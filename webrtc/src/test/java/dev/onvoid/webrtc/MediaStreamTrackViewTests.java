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

import static org.junit.jupiter.api.Assertions.*;

import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.MediaStreamTrackMuteListener;
import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Tracks obtained from a sender, a receiver or a track listener are views of
 * a track owned elsewhere: disposing one must not release the track.
 */
@Execution(ExecutionMode.SAME_THREAD)
class MediaStreamTrackViewTests extends TestBase {

	private TestPeerConnection connection;

	private AudioTrackSource audioSource;

	private AudioTrack audioTrack;


	@BeforeEach
	void init() {
		connection = new TestPeerConnection(factory);
		audioSource = factory.createAudioSource(new AudioOptions());
		audioTrack = factory.createAudioTrack("audioTrack", audioSource);
	}

	@AfterEach
	void dispose() {
		// The track goes after the connection, whose sender holds it. Its
		// disposal fails if a view released a reference it did not own.
		connection.close();
		audioTrack.dispose();
		audioSource.dispose();
	}

	@Test
	void disposeSenderTrackView() {
		RTCRtpSender sender = connection.getPeerConnection()
				.addTrack(audioTrack, Collections.singletonList("stream"));

		MediaStreamTrack view = sender.getTrack();

		assertDoesNotThrow(view::dispose);
		assertThrows(NullPointerException.class, view::getId);

		// The track itself is untouched.
		assertEquals(audioTrack.getId(), sender.getTrack().getId());
		assertTrue(audioTrack.isEnabled());

		sender.dispose();
	}

	@Test
	void disposeReceiverTrackView() {
		RTCRtpTransceiver transceiver = connection.getPeerConnection()
				.addTransceiver(audioTrack, new RTCRtpTransceiverInit());
		RTCRtpReceiver receiver = transceiver.getReceiver();

		MediaStreamTrack view = receiver.getTrack();
		String id = view.getId();

		assertDoesNotThrow(view::dispose);
		assertEquals(id, receiver.getTrack().getId());

		receiver.dispose();
		transceiver.dispose();
	}

	@Test
	void disposeListenerTrackView() {
		List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
		MediaStreamTrackMuteListener listener = (track, muted) -> {
			try {
				track.dispose();
			}
			catch (Throwable e) {
				errors.add(e);
			}
		};

		audioTrack.addTrackMuteListener(listener);
		audioTrack.setEnabled(false);
		audioTrack.removeTrackMuteListener(listener);

		assertTrue(errors.isEmpty(), () -> "Errors: " + errors);
		assertFalse(audioTrack.isEnabled());
	}
}
