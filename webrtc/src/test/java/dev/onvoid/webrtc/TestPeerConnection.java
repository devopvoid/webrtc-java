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

import static java.util.Objects.nonNull;

import java.util.concurrent.CountDownLatch;

/**
 * Convenience test class implementing the basic {@link RTCPeerConnection}
 * functionality to establish a connection to a remote peer.
 *
 * @author Alex Andres
 */
class TestPeerConnection implements PeerConnectionObserver {

	private final CountDownLatch connectedLatch;

	private RTCPeerConnection localPeerConnection;

	private RTCPeerConnection remotePeerConnection;


	TestPeerConnection(PeerConnectionFactory factory) {
		RTCConfiguration config = new RTCConfiguration();

		localPeerConnection = factory.createPeerConnection(config, this);
		localPeerConnection.createDataChannel("dummy", new RTCDataChannelInit());

		connectedLatch = new CountDownLatch(1);
	}

	@Override
	public void onIceCandidate(RTCIceCandidate candidate) {
		remotePeerConnection.addIceCandidate(candidate);
	}

	@Override
	public void onConnectionChange(RTCPeerConnectionState state) {
		if (state == RTCPeerConnectionState.CONNECTED) {
			connectedLatch.countDown();
		}
	}

	void waitUntilConnected() throws InterruptedException {
		connectedLatch.await();
	}

	RTCSessionDescription createOffer() throws Exception {
		TestCreateDescObserver createObserver = new TestCreateDescObserver();
		TestSetDescObserver setObserver = new TestSetDescObserver();

		localPeerConnection.createOffer(new RTCOfferOptions(), createObserver);

		RTCSessionDescription offerDesc = createObserver.get();

		localPeerConnection.setLocalDescription(offerDesc, setObserver);
		setObserver.get();

		return offerDesc;
	}

	RTCSessionDescription createAnswer() throws Exception {
		TestCreateDescObserver createObserver = new TestCreateDescObserver();
		TestSetDescObserver setObserver = new TestSetDescObserver();

		localPeerConnection.createAnswer(new RTCAnswerOptions(), createObserver);

		RTCSessionDescription answerDesc = createObserver.get();

		localPeerConnection.setLocalDescription(answerDesc, setObserver);
		setObserver.get();

		return answerDesc;
	}

	void setRemotePeerConnection(TestPeerConnection connection) {
		this.remotePeerConnection = connection.localPeerConnection;
	}

	void setRemoteDescription(RTCSessionDescription description) throws Exception {
		TestSetDescObserver setObserver = new TestSetDescObserver();

		localPeerConnection.setRemoteDescription(description, setObserver);
		setObserver.get();
	}

	RTCPeerConnection getPeerConnection() {
		return localPeerConnection;
	}

	void close() {
		if (nonNull(localPeerConnection)) {
			localPeerConnection.close();
			localPeerConnection = null;
		}
	}
}
