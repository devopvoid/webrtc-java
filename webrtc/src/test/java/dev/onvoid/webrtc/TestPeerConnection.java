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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Assertions;

/**
 * Convenience test class implementing the basic {@link RTCPeerConnection}
 * functionality to establish a connection to a remote peer and send text
 * messages via the {@link RTCDataChannel}.
 *
 * @author Alex Andres
 */
class TestPeerConnection implements PeerConnectionObserver {

	private final List<String> receivedTexts;

	private final CountDownLatch connectedLatch;

	private RTCPeerConnection localPeerConnection;

	private RTCPeerConnection remotePeerConnection;

	private RTCDataChannel localDataChannel;

	private RTCDataChannel remoteDataChannel;


	TestPeerConnection(PeerConnectionFactory factory) {
		RTCConfiguration config = new RTCConfiguration();

		localPeerConnection = factory.createPeerConnection(config, this);
		localDataChannel = localPeerConnection.createDataChannel("dc", new RTCDataChannelInit());
		receivedTexts = new ArrayList<>();
		connectedLatch = new CountDownLatch(1);
	}

	@Override
	public void onIceCandidate(RTCIceCandidate candidate) {
		remotePeerConnection.addIceCandidate(candidate);
	}

	@Override
	public void onDataChannel(RTCDataChannel dataChannel) {
		remoteDataChannel = dataChannel;
		remoteDataChannel.registerObserver(new RTCDataChannelObserver() {

			@Override
			public void onBufferedAmountChange(long previousAmount) { }

			@Override
			public void onStateChange() { }

			@Override
			public void onMessage(RTCDataChannelBuffer buffer) {
				try {
					decodeMessage(buffer);
				}
				catch (Exception e) {
					Assertions.fail(e);
				}
			}
		});
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

	void sendTextMessage(String message) throws Exception {
		ByteBuffer data = ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));
		RTCDataChannelBuffer buffer = new RTCDataChannelBuffer(data, false);

		localDataChannel.send(buffer);
	}

	RTCPeerConnection getPeerConnection() {
		return localPeerConnection;
	}

	List<String> getReceivedTexts() {
		return receivedTexts;
	}

	void close() {
		if (nonNull(localDataChannel)) {
			localDataChannel.unregisterObserver();
			localDataChannel.close();
			localDataChannel.dispose();
			localDataChannel = null;
		}
		if (nonNull(remoteDataChannel)) {
			remoteDataChannel.unregisterObserver();
			remoteDataChannel.close();
			remoteDataChannel.dispose();
			remoteDataChannel = null;
		}
		if (nonNull(localPeerConnection)) {
			localPeerConnection.close();
			localPeerConnection = null;
		}
	}

	private void decodeMessage(RTCDataChannelBuffer buffer) {
		ByteBuffer byteBuffer = buffer.data;
		byte[] payload;

		if (byteBuffer.hasArray()) {
			payload = byteBuffer.array();
		}
		else {
			payload = new byte[byteBuffer.limit()];

			byteBuffer.get(payload);
		}

		String text = new String(payload, StandardCharsets.UTF_8);

		receivedTexts.add(text);
	}

}
