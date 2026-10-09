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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.MediaType;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.CustomAudioSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class RTCRtpSenderReceiverTests extends TestBase {

	@Test
	void firstPacketEvents() throws Exception {
		try (TestMediaCall call = new TestMediaCall(factory, true, true)) {
			call.negotiate();

			RTCRtpReceiver videoReceiver = call.getReceiver(MediaStreamTrack.VIDEO_TRACK_KIND);
			RTCRtpReceiver audioReceiver = call.getReceiver(MediaStreamTrack.AUDIO_TRACK_KIND);

			CountDownLatch videoReceived = new CountDownLatch(1);
			CountDownLatch audioReceived = new CountDownLatch(1);
			CountDownLatch videoSent = new CountDownLatch(1);
			AtomicReference<MediaType> videoType = new AtomicReference<>();
			AtomicReference<MediaType> sentType = new AtomicReference<>();

			videoReceiver.setObserver(type -> {
				videoType.set(type);
				videoReceived.countDown();
			});
			audioReceiver.setObserver(type -> audioReceived.countDown());
			call.getVideoSender().setObserver(type -> {
				sentType.set(type);
				videoSent.countDown();
			});

			try {
				call.awaitConnected();
				call.startMedia();

				assertTrue(videoSent.await(10, TimeUnit.SECONDS), "No first video packet sent");
				assertTrue(videoReceived.await(10, TimeUnit.SECONDS), "No first video packet received");
				assertTrue(audioReceived.await(10, TimeUnit.SECONDS), "No first audio packet received");

				assertEquals(MediaType.VIDEO, sentType.get());
				assertEquals(MediaType.VIDEO, videoType.get());

				// An observer set late is told right away, before setObserver
				// returns.
				CountDownLatch late = new CountDownLatch(1);
				videoReceiver.setObserver(type -> late.countDown());

				assertEquals(0, late.getCount());

				CountDownLatch lateSent = new CountDownLatch(1);
				call.getVideoSender().setObserver(type -> lateSent.countDown());

				assertEquals(0, lateSent.getCount());

				videoReceiver.setObserver(null);
				call.getVideoSender().setObserver(null);
			}
			finally {
				// Disposing removes the observers set through these objects.
				videoReceiver.dispose();
				audioReceiver.dispose();
			}
		}
	}

	@Test
	void senderIdAndStreams() {
		RTCPeerConnection connection = factory.createPeerConnection(new RTCConfiguration(), candidate -> { });
		// Custom audio, like the calls of the other tests: a factory sends
		// either device or custom audio.
		AudioTrack track = factory.createAudioTrack("audioTrack", new CustomAudioSource());

		try {
			RTCRtpSender sender = connection.addTrack(track, Arrays.asList("stream-a", "stream-b"));

			assertFalse(sender.getId().isEmpty());
			assertEquals(Arrays.asList("stream-a", "stream-b"), sender.getStreams());

			sender.setStreams(Collections.singletonList("stream-c"));

			assertEquals(Collections.singletonList("stream-c"), sender.getStreams());

			// Every object of the same sender has its ID.
			RTCRtpSender[] senders = connection.getSenders();

			assertEquals(sender.getId(), senders[0].getId());

			for (RTCRtpSender other : senders) {
				other.dispose();
			}
		}
		finally {
			connection.close();
		}
	}

	@Test
	void receiverId() {
		RTCPeerConnection connection = factory.createPeerConnection(new RTCConfiguration(), candidate -> { });

		try {
			RTCRtpTransceiver transceiver = connection.addTransceiver(
					factory.createAudioTrack("audio", new CustomAudioSource()),
					new RTCRtpTransceiverInit());

			RTCRtpReceiver receiver = transceiver.getReceiver();
			RTCRtpReceiver[] receivers = connection.getReceivers();

			assertFalse(receiver.getId().isEmpty());
			assertEquals(receiver.getId(), receivers[0].getId());

			// Setting and removing an observer before any media flows.
			receiver.setObserver(type -> { });
			receiver.setObserver(null);

			for (RTCRtpReceiver other : receivers) {
				other.dispose();
			}
			receiver.dispose();
			transceiver.dispose();
		}
		finally {
			connection.close();
		}
	}

	@Test
	void remoteTrackVolume() throws Exception {
		try (TestMediaCall call = new TestMediaCall(factory, false, true)) {
			call.negotiate();

			RTCRtpReceiver receiver = call.getReceiver(MediaStreamTrack.AUDIO_TRACK_KIND);

			try {
				AudioTrack track = (AudioTrack) receiver.getTrack();

				track.setVolume(0);
				track.setVolume(0.5);
				track.setVolume(10);

				for (double invalid : new double[] { -0.1, 10.1, Double.NaN }) {
					assertThrows(IllegalArgumentException.class, () -> track.setVolume(invalid));
				}
			}
			finally {
				receiver.dispose();
			}
		}
	}

	@Test
	void localTrackIgnoresVolume() {
		AudioTrack track = factory.createAudioTrack("audioTrack", new CustomAudioSource());

		try {
			track.setVolume(2);
		}
		finally {
			track.dispose();
		}
	}

	@Test
	void streamsOfSenderWithoutStreams() {
		RTCPeerConnection connection = factory.createPeerConnection(new RTCConfiguration(), candidate -> { });
		AudioTrack track = factory.createAudioTrack("audioTrack", new CustomAudioSource());

		try {
			RTCRtpSender sender = connection.addTrack(track, Collections.emptyList());
			List<String> streams = sender.getStreams();

			assertTrue(streams.isEmpty(), streams.toString());
		}
		finally {
			connection.close();
		}
	}
}
