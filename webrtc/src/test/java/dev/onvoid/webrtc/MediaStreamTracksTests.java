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
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;
import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.VideoTrack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * MediaStream.getAudioTracks() and getVideoTracks() return the arrays native
 * code made: an array whose local reference was deleted on the way out reached
 * Java as null, whether the stream had tracks of that kind or none.
 */
@Execution(ExecutionMode.SAME_THREAD)
class MediaStreamTracksTests extends TestBase {

	private final List<String> audioTrackIds = Collections.synchronizedList(new ArrayList<>());

	private final List<String> videoTrackIds = Collections.synchronizedList(new ArrayList<>());

	private final List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

	private RTCPeerConnection caller;

	private RTCPeerConnection callee;

	private RTCRtpSender sender;

	private MediaStreamTrack track;

	private AutoCloseable source;


	@BeforeEach
	void init() {
		// TestBase keeps one instance for all tests of the class.
		sender = null;
		track = null;
		source = null;
		audioTrackIds.clear();
		videoTrackIds.clear();
		errors.clear();

		RTCConfiguration config = new RTCConfiguration();

		caller = factory.createPeerConnection(config, candidate -> { });
		callee = factory.createPeerConnection(config, new PeerConnectionObserver() {

			@Override
			public void onIceCandidate(RTCIceCandidate candidate) {
			}

			@Override
			public void onAddTrack(RTCRtpReceiver receiver, MediaStream[] mediaStreams) {
				// The remote stream is alive here: the peer connection holds it.
				try {
					for (MediaStream stream : mediaStreams) {
						collect(stream.getAudioTracks(), audioTrackIds);
						collect(stream.getVideoTracks(), videoTrackIds);

						stream.dispose();
					}

					receiver.dispose();
				}
				catch (Throwable e) {
					errors.add(e);
				}
			}
		});
	}

	@AfterEach
	void dispose() throws Exception {
		if (sender != null) {
			sender.dispose();
		}

		caller.close();
		callee.close();

		if (track != null) {
			track.dispose();
		}
		if (source != null) {
			source.close();
		}
	}

	@Test
	void remoteAudioStreamTracks() throws Exception {
		AudioTrackSource audioSource = factory.createAudioSource(new AudioOptions());
		source = audioSource::dispose;

		AudioTrack audioTrack = factory.createAudioTrack("audioTrack", audioSource);
		track = audioTrack;

		sender = caller.addTrack(audioTrack, Collections.singletonList("stream"));

		negotiate();

		assertTrue(errors.isEmpty(), () -> "Errors: " + errors);
		assertEquals(Collections.singletonList("audioTrack"), audioTrackIds);
		assertEquals(Collections.emptyList(), videoTrackIds);
	}

	@Test
	void remoteVideoStreamTracks() throws Exception {
		CustomVideoSource videoSource = new CustomVideoSource();
		source = videoSource::dispose;

		VideoTrack videoTrack = factory.createVideoTrack("videoTrack", videoSource);
		track = videoTrack;

		sender = caller.addTrack(videoTrack, Collections.singletonList("stream"));

		negotiate();

		assertTrue(errors.isEmpty(), () -> "Errors: " + errors);
		assertEquals(Collections.emptyList(), audioTrackIds);
		assertEquals(Collections.singletonList("videoTrack"), videoTrackIds);
	}

	private static void collect(MediaStreamTrack[] tracks, List<String> ids) {
		// An empty array, not null, for a stream without tracks of the kind.
		assertNotNull(tracks);

		for (MediaStreamTrack track : tracks) {
			ids.add(track.getId());

			// The tracks are views of the stream's tracks.
			track.dispose();
		}
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
