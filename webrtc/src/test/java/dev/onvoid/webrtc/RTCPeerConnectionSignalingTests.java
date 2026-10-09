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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RTCPeerConnectionSignalingTests extends TestBase {

	private Peer caller;

	private Peer callee;


	@BeforeEach
	void init() {
		caller = new Peer();
		callee = new Peer();
	}

	@AfterEach
	void dispose() {
		caller.close();
		callee.close();
	}

	@Test
	void implicitOfferAndAnswer() throws Exception {
		caller.connection.createDataChannel("data", new RTCDataChannelInit());

		negotiate();

		assertEquals(RTCSdpType.OFFER, callee.connection.getRemoteDescription().sdpType);
		assertEquals(RTCSdpType.ANSWER, caller.connection.getRemoteDescription().sdpType);
		assertEquals(RTCSignalingState.STABLE, caller.connection.getSignalingState());
		assertEquals(RTCSignalingState.STABLE, callee.connection.getSignalingState());

		exchangeCandidates();

		assertTrue(caller.connected.await(10, TimeUnit.SECONDS), "Caller did not connect");
		assertTrue(callee.connected.await(10, TimeUnit.SECONDS), "Callee did not connect");
	}

	@Test
	void candidatesAddedWithObserver() throws Exception {
		caller.connection.createDataChannel("data", new RTCDataChannelInit());

		negotiate();

		List<RTCIceCandidate> callerCandidates = caller.awaitCandidates();
		assertFalse(callerCandidates.isEmpty());

		for (RTCIceCandidate candidate : callerCandidates) {
			TestAddIceObserver observer = new TestAddIceObserver();
			callee.connection.addIceCandidate(candidate, observer);
			observer.get(5, TimeUnit.SECONDS);
		}

		RTCIceCandidate added = callerCandidates.get(0);
		// The SDP leaves out the candidate's ufrag when it is the session's.
		String candidateLine = "a=" + added.sdp.substring(0, added.sdp.indexOf(" generation"));

		assertTrue(callee.connection.getRemoteDescription().sdp.contains(candidateLine));

		// Removal matches the candidate by its MID and attribute.
		assertTrue(callee.connection.removeIceCandidate(added));
		assertFalse(callee.connection.getRemoteDescription().sdp.contains(candidateLine));
		assertFalse(callee.connection.removeIceCandidate(added));
	}

	@Test
	void unknownMidFailsWithObserver() throws Exception {
		caller.connection.createDataChannel("data", new RTCDataChannelInit());

		negotiate();

		// The candidate parses, but no transport has this MID.
		RTCIceCandidate candidate = new RTCIceCandidate("unknown", 5,
				"candidate:1 1 udp 2122260223 192.168.1.10 54321 typ host generation 0");
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<String> failure = new AtomicReference<>();

		callee.connection.addIceCandidate(candidate, new AddIceCandidateObserver() {
			@Override
			public void onSuccess() {
				done.countDown();
			}

			@Override
			public void onFailure(String error) {
				failure.set(error);
				done.countDown();
			}
		});

		assertTrue(done.await(5, TimeUnit.SECONDS));
		assertNotNull(failure.get(), "An unknown media section was accepted");
	}

	@Test
	void selectedCandidatePairEvent() throws Exception {
		caller.connection.createDataChannel("data", new RTCDataChannelInit());

		negotiate();
		exchangeCandidates();

		assertTrue(caller.pairChanged.await(10, TimeUnit.SECONDS), "No pair change event");

		RTCCandidatePairChangeEvent event = caller.pairChange.get();

		assertNotNull(event.getLocal());
		assertNotNull(event.getRemote());
		assertNotNull(event.getLocal().sdpMid);
		assertEquals(event.getLocal().sdpMid, event.getRemote().sdpMid);
		assertEquals(-1, event.getRemote().sdpMLineIndex);
		assertTrue(event.getLocal().sdp.startsWith("candidate:"), event.getLocal().sdp);
		assertTrue(event.getRemote().sdp.startsWith("candidate:"), event.getRemote().sdp);
		assertNotNull(event.getReason());
		assertFalse(event.getReason().isEmpty());

		// The remote candidate in the event is one the callee gathered: its
		// port is the one the older callback reports.
		assertTrue(event.getRemote().sdp.contains(" " + event.remotePort + " typ "),
				event.getRemote().sdp);
	}

	@Test
	void legacyPairCallbackStillFires() throws Exception {
		CountDownLatch legacy = new CountDownLatch(1);
		AtomicReference<String> type = new AtomicReference<>();

		Peer legacyCaller = new Peer() {
			@Override
			public void onSelectedCandidatePairChanged(String remoteAddress,
					int remotePort, String candidateType) {
				type.set(candidateType);
				legacy.countDown();
			}
		};

		Peer previous = caller;
		caller = legacyCaller;
		previous.close();

		caller.connection.createDataChannel("data", new RTCDataChannelInit());

		negotiate();
		exchangeCandidates();

		assertTrue(legacy.await(10, TimeUnit.SECONDS), "The older callback was not called");
		assertNotNull(type.get());
	}

	@Test
	void remoteChannelHasAnnouncedPriority() throws Exception {
		RTCDataChannelInit init = new RTCDataChannelInit();
		init.priority = RTCPriorityType.HIGH;

		RTCDataChannel channel = caller.connection.createDataChannel("data", init);

		negotiate();
		exchangeCandidates();

		assertTrue(callee.dataChannelReceived.await(10, TimeUnit.SECONDS), "No data channel announced");

		RTCDataChannel remote = callee.dataChannel.get();

		assertEquals("data", remote.getLabel());
		assertEquals(RTCPriorityType.HIGH, remote.getPriority());

		channel.dispose();
		remote.dispose();
	}

	/**
	 * Offer and answer through the implicit form of setLocalDescription.
	 */
	private void negotiate() throws Exception {
		setLocal(caller);
		setRemote(callee, caller.connection.getLocalDescription());
		setLocal(callee);
		setRemote(caller, callee.connection.getLocalDescription());
	}

	private void exchangeCandidates() {
		caller.sendCandidatesTo(callee);
		callee.sendCandidatesTo(caller);
	}

	private static void setLocal(Peer peer) throws Exception {
		TestSetDescObserver observer = new TestSetDescObserver();
		peer.connection.setLocalDescription(observer);
		observer.get(5, TimeUnit.SECONDS);
	}

	private static void setRemote(Peer peer, RTCSessionDescription description)
			throws Exception {
		TestSetDescObserver observer = new TestSetDescObserver();
		peer.connection.setRemoteDescription(description, observer);
		observer.get(5, TimeUnit.SECONDS);
	}



	private class Peer implements PeerConnectionObserver {

		final RTCPeerConnection connection;

		final List<RTCIceCandidate> candidates = new CopyOnWriteArrayList<>();

		final CountDownLatch gathered = new CountDownLatch(1);

		final CountDownLatch connected = new CountDownLatch(1);

		final CountDownLatch pairChanged = new CountDownLatch(1);

		final AtomicReference<RTCCandidatePairChangeEvent> pairChange = new AtomicReference<>();

		final CountDownLatch dataChannelReceived = new CountDownLatch(1);

		final AtomicReference<RTCDataChannel> dataChannel = new AtomicReference<>();

		private final List<RTCIceCandidate> pending = new ArrayList<>();

		/* Set once both descriptions are in place, guarded by this peer. */
		private Peer remote;


		Peer() {
			connection = factory.createPeerConnection(new RTCConfiguration(), this);
		}

		@Override
		public void onIceCandidate(RTCIceCandidate candidate) {
			candidates.add(candidate);

			Peer target;

			synchronized (this) {
				if (remote == null) {
					pending.add(candidate);
					return;
				}
				target = remote;
			}

			target.connection.addIceCandidate(candidate, new TestAddIceObserver());
		}

		@Override
		public void onIceGatheringChange(RTCIceGatheringState state) {
			if (state == RTCIceGatheringState.COMPLETE) {
				gathered.countDown();
			}
		}

		@Override
		public void onConnectionChange(RTCPeerConnectionState state) {
			if (state == RTCPeerConnectionState.CONNECTED) {
				connected.countDown();
			}
		}

		@Override
		public void onSelectedCandidatePairChanged(RTCCandidatePairChangeEvent event) {
			pairChange.compareAndSet(null, event);
			pairChanged.countDown();

			PeerConnectionObserver.super.onSelectedCandidatePairChanged(event);
		}

		@Override
		public void onDataChannel(RTCDataChannel channel) {
			dataChannel.compareAndSet(null, channel);
			dataChannelReceived.countDown();
		}

		List<RTCIceCandidate> awaitCandidates() throws InterruptedException {
			assertTrue(gathered.await(10, TimeUnit.SECONDS), "Gathering did not complete");

			return new ArrayList<>(candidates);
		}

		/*
		 * Passes the candidates gathered so far, and all later ones, to the
		 * other peer.
		 */
		void sendCandidatesTo(Peer peer) {
			List<RTCIceCandidate> queued;

			synchronized (this) {
				remote = peer;
				queued = new ArrayList<>(pending);
				pending.clear();
			}

			for (RTCIceCandidate candidate : queued) {
				peer.connection.addIceCandidate(candidate, new TestAddIceObserver());
			}
		}

		void close() {
			connection.close();
		}
	}
}
