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

import dev.onvoid.webrtc.media.MediaStream;
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
 * The remote streams handed to onAddTrack are views of streams the peer
 * connection owns: disposing one must not release the stream.
 */
@Execution(ExecutionMode.SAME_THREAD)
class MediaStreamViewTests extends TestBase {

	private final List<String> streamIds = Collections.synchronizedList(new ArrayList<>());

	private final List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

	private RTCPeerConnection caller;

	private RTCPeerConnection callee;

	private AudioTrackSource audioSource;

	private AudioTrack audioTrack;

	private RTCRtpSender sender;


	@BeforeEach
	void init() {
		// TestBase keeps one instance for all tests of the class.
		streamIds.clear();
		errors.clear();

		RTCConfiguration config = new RTCConfiguration();

		caller = factory.createPeerConnection(config, candidate -> { });
		callee = factory.createPeerConnection(config, new PeerConnectionObserver() {

			@Override
			public void onIceCandidate(RTCIceCandidate candidate) {
			}

			@Override
			public void onAddTrack(RTCRtpReceiver receiver, MediaStream[] mediaStreams) {
				try {
					for (MediaStream stream : mediaStreams) {
						streamIds.add(stream.id());

						stream.dispose();
					}

					receiver.dispose();
				}
				catch (Throwable e) {
					errors.add(e);
				}
			}
		});

		audioSource = factory.createAudioSource(new AudioOptions());
		audioTrack = factory.createAudioTrack("audioTrack", audioSource);
		sender = caller.addTrack(audioTrack, Collections.singletonList("stream-0"));
	}

	@AfterEach
	void dispose() {
		// Closing the callee tears down the remote stream, which fails if a
		// disposed view released the reference the peer connection holds.
		sender.dispose();
		caller.close();
		callee.close();
		audioTrack.dispose();
		audioSource.dispose();
	}

	@Test
	void disposeRemoteStreamView() throws Exception {
		negotiate();

		assertTrue(errors.isEmpty(), () -> "Errors: " + errors);
		assertEquals(Collections.singletonList("stream-0"), streamIds);

		// A second offer goes over the same remote stream again.
		negotiate();

		assertTrue(errors.isEmpty(), () -> "Errors: " + errors);
	}

	@Test
	void removeTrackAfterDisposingStreamView() throws Exception {
		negotiate();

		// Removing the track empties the remote stream, which the peer
		// connection then takes apart: a stream freed early by a disposed
		// view is used after it was freed.
		caller.removeTrack(sender);
		negotiate();

		assertTrue(errors.isEmpty(), () -> "Errors: " + errors);
		assertEquals(Collections.singletonList("stream-0"), streamIds);
	}

	private void negotiate() throws Exception {
		RTCSessionDescription offer = createDescription(caller, true);
		setRemoteDescription(callee, offer);

		RTCSessionDescription answer = createDescription(callee, false);
		setRemoteDescription(caller, answer);
	}

	private static RTCSessionDescription createDescription(RTCPeerConnection connection, boolean offer) throws Exception {
		TestCreateDescObserver createObserver = new TestCreateDescObserver();
		TestSetDescObserver setObserver = new TestSetDescObserver();

		if (offer) {
			connection.createOffer(new RTCOfferOptions(), createObserver);
		}
		else {
			connection.createAnswer(new RTCAnswerOptions(), createObserver);
		}

		RTCSessionDescription description = createObserver.get();

		connection.setLocalDescription(description, setObserver);
		setObserver.get();

		return description;
	}

	private static void setRemoteDescription(RTCPeerConnection connection, RTCSessionDescription description) throws Exception {
		TestSetDescObserver setObserver = new TestSetDescObserver();

		connection.setRemoteDescription(description, setObserver);
		setObserver.get();
	}
}
