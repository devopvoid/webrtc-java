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

package dev.onvoid.webrtc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link RTCDtlsTransport} class.
 */
class RTCDtlsTransportTests extends TestBase {

	private TestPeerConnection caller;
	private TestPeerConnection callee;

	private RTCRtpSender sender;

	private RTCDtlsTransport transport;


	@BeforeEach
	void init() throws Exception {
		caller = new TestPeerConnection(factory);
		callee = new TestPeerConnection(factory);

		AudioTrackSource audioSource = factory.createAudioSource(new AudioOptions());
		AudioTrack audioTrack = factory.createAudioTrack("audioTrack", audioSource);

		List<String> streamIds = new ArrayList<>();
		streamIds.add("stream1");

		sender = caller.getPeerConnection().addTrack(audioTrack, streamIds);

		caller.setRemotePeerConnection(callee);
		callee.setRemotePeerConnection(caller);

		callee.setRemoteDescription(caller.createOffer());
		caller.setRemoteDescription(callee.createAnswer());

		caller.waitUntilConnected();
		callee.waitUntilConnected();

		transport = sender.getTransport();
	}

	@AfterEach
	void dispose() {
		caller.close();
		callee.close();

		if (transport != null) {
			transport.dispose();
		}
	}

	@Test
	void noTransportBeforeNegotiation() {
		TestPeerConnection offerer = new TestPeerConnection(factory);

		try {
			AudioTrackSource audioSource = factory.createAudioSource(new AudioOptions());
			AudioTrack audioTrack = factory.createAudioTrack("audioTrack", audioSource);
			RTCRtpSender audioSender = offerer.getPeerConnection()
					.addTrack(audioTrack, Collections.singletonList("stream1"));

			assertNull(audioSender.getTransport());
		}
		finally {
			offerer.close();
		}
	}

	@Test
	void replaceObserverStopsPreviousObserver() {
		assertNotNull(transport);

		AtomicInteger firstObserverCalls = new AtomicInteger();
		AtomicInteger secondObserverCalls = new AtomicInteger();

		RTCDtlsTransportObserver first = new RTCDtlsTransportObserver() {
			@Override
			public void onStateChange(RTCDtlsTransportState info) {
				firstObserverCalls.incrementAndGet();
			}

			@Override
			public void onError(String error) { }
		};
		RTCDtlsTransportObserver second = new RTCDtlsTransportObserver() {
			@Override
			public void onStateChange(RTCDtlsTransportState info) {
				secondObserverCalls.incrementAndGet();
			}

			@Override
			public void onError(String error) { }
		};

		transport.registerObserver(first);

		// Replacing the observer must unregister and free the previous
		// native observer wrapper instead of leaking it and leaving it
		// registered alongside the new one.
		assertDoesNotThrow(() -> transport.registerObserver(second));

		int firstCallsAfterReplace = firstObserverCalls.get();

		// Registering and unregistering repeatedly must not leak or crash;
		// this used to leak the previous native observer on every call.
		for (int i = 0; i < 50; i++) {
			transport.registerObserver(second);
		}

		transport.unregisterObserver();

		// Unregistering with nothing registered anymore must be a safe no-op.
		assertDoesNotThrow(transport::unregisterObserver);

		assertEquals(firstCallsAfterReplace, firstObserverCalls.get(),
				"The replaced observer must not receive further events");
	}

	@Test
	void remoteCertificates() {
		List<RTCCertificatePEM> certificates = transport.getRemoteCertificates();

		assertFalse(certificates.isEmpty());

		for (RTCCertificatePEM certificate : certificates) {
			assertTrue(certificate.getCertificate().startsWith("-----BEGIN CERTIFICATE-----"));
		}
	}

	@Test
	void remoteCertificatesBeforeHandshake() throws Exception {
		TestPeerConnection offerer = new TestPeerConnection(factory);

		try {
			AudioTrackSource audioSource = factory.createAudioSource(new AudioOptions());
			AudioTrack audioTrack = factory.createAudioTrack("audioTrack", audioSource);
			RTCRtpSender audioSender = offerer.getPeerConnection()
					.addTrack(audioTrack, Collections.singletonList("stream1"));

			// Setting the local description creates the transport, which has
			// no remote certificates before the handshake. Reading them used
			// to crash the VM.
			offerer.createOffer();

			RTCDtlsTransport dtlsTransport = audioSender.getTransport();
			assertNotNull(dtlsTransport);
			assertEquals(RTCDtlsTransportState.NEW, dtlsTransport.getState());

			assertTrue(dtlsTransport.getRemoteCertificates().isEmpty());

			dtlsTransport.dispose();
		}
		finally {
			offerer.close();
		}
	}

	@Test
	void transportOutlivesClosedConnection() throws Exception {
		CompletableFuture<RTCDtlsTransportState> closedState = new CompletableFuture<>();

		transport.registerObserver(new RTCDtlsTransportObserver() {
			@Override
			public void onStateChange(RTCDtlsTransportState state) {
				if (state == RTCDtlsTransportState.CLOSED) {
					closedState.complete(state);
				}
			}

			@Override
			public void onError(String error) { }
		});

		// Closing frees the connection's transports, and with the sender gone
		// nothing else refers to this one. The wrapper must keep the transport
		// it refers to alive, rather than point to freed memory.
		caller.close();
		callee.close();
		sender.dispose();

		assertEquals(RTCDtlsTransportState.CLOSED, closedState.get(5, TimeUnit.SECONDS));

		for (int i = 0; i < 1000; i++) {
			assertEquals(RTCDtlsTransportState.CLOSED, transport.getState());
			assertTrue(transport.getRemoteCertificates().isEmpty());
		}

		assertDoesNotThrow(transport::unregisterObserver);
	}

	@Test
	void unregisterKeepsObserverOfOtherInstance() throws Exception {
		assertObserverOfOtherInstanceStays(false);
	}

	@Test
	void disposeKeepsObserverOfOtherInstance() throws Exception {
		assertObserverOfOtherInstanceStays(true);
	}

	private void assertObserverOfOtherInstanceStays(boolean dispose) throws Exception {
		RTCDtlsTransport other = sender.getTransport();

		AtomicInteger firstObserverCalls = new AtomicInteger();
		CompletableFuture<RTCDtlsTransportState> closedState = new CompletableFuture<>();

		try {
			transport.registerObserver(new RTCDtlsTransportObserver() {
				@Override
				public void onStateChange(RTCDtlsTransportState state) {
					firstObserverCalls.incrementAndGet();
				}

				@Override
				public void onError(String error) { }
			});
			other.registerObserver(new RTCDtlsTransportObserver() {
				@Override
				public void onStateChange(RTCDtlsTransportState state) {
					if (state == RTCDtlsTransportState.CLOSED) {
						closedState.complete(state);
					}
				}

				@Override
				public void onError(String error) { }
			});

			// The observer registered through the other instance replaced the
			// first one, which leaves this instance nothing to remove from the
			// transport.
			if (dispose) {
				transport.dispose();
				transport = null;
			}
			else {
				transport.unregisterObserver();
			}

			caller.close();
			callee.close();
			sender.dispose();

			assertEquals(RTCDtlsTransportState.CLOSED, closedState.get(5, TimeUnit.SECONDS),
					"The observer of the other instance was removed");
			assertEquals(0, firstObserverCalls.get(), "The replaced observer must not receive events");
		}
		finally {
			other.dispose();
		}
	}

	@Test
	void observerCanUseRtpSender() throws Exception {
		// WebRTC reports DTLS states on the network thread, which is also the
		// worker thread. Getting a sender's parameters blocks the signaling
		// thread on the worker thread, which deadlocks should the observer
		// run on that thread.
		TestPeerConnection offerer = new TestPeerConnection(factory);
		TestPeerConnection answerer = new TestPeerConnection(factory);

		try {
			AudioTrackSource audioSource = factory.createAudioSource(new AudioOptions());
			AudioTrack audioTrack = factory.createAudioTrack("audioTrack", audioSource);
			RTCRtpSender audioSender = offerer.getPeerConnection()
					.addTrack(audioTrack, Collections.singletonList("stream1"));

			offerer.setRemotePeerConnection(answerer);
			answerer.setRemotePeerConnection(offerer);

			RTCSessionDescription offer = offerer.createOffer();
			RTCDtlsTransport dtlsTransport = audioSender.getTransport();
			assertNotNull(dtlsTransport);

			CompletableFuture<RTCRtpSendParameters> parameters = new CompletableFuture<>();
			dtlsTransport.registerObserver(new RTCDtlsTransportObserver() {
				@Override
				public void onStateChange(RTCDtlsTransportState state) {
					if (state == RTCDtlsTransportState.CONNECTED) {
						parameters.complete(audioSender.getParameters());
					}
				}

				@Override
				public void onError(String error) {
					parameters.completeExceptionally(new IllegalStateException(error));
				}
			});

			answerer.setRemoteDescription(offer);
			offerer.setRemoteDescription(answerer.createAnswer());

			assertNotNull(parameters.get(10, TimeUnit.SECONDS));

			dtlsTransport.unregisterObserver();
			dtlsTransport.dispose();
		}
		finally {
			offerer.close();
			answerer.close();
		}
	}

}
