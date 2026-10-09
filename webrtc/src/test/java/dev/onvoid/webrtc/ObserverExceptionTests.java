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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An exception thrown by an application callback that WebRTC invokes used to
 * be rethrown as a C++ exception through WebRTC's frames, which leaks WebRTC's
 * locks or ends the process. It now goes to the thread's uncaught exception
 * handler, and WebRTC carries on.
 */
class ObserverExceptionTests extends TestBase {

	private final List<Throwable> uncaught = new CopyOnWriteArrayList<>();

	private Thread.UncaughtExceptionHandler previousHandler;


	@BeforeEach
	void captureUncaught() {
		uncaught.clear();
		previousHandler = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler((thread, error) -> uncaught.add(error));
	}

	@AfterEach
	void restoreHandler() {
		Thread.setDefaultUncaughtExceptionHandler(previousHandler);
	}

	@Test
	void statsCallbackThrows() throws Exception {
		RTCPeerConnection connection = factory.createPeerConnection(new RTCConfiguration(), candidate -> { });

		try {
			connection.getStats(report -> {
				throw new CallbackFailure("stats");
			});

			awaitUncaught("stats");

			// The connection still delivers stats.
			CountDownLatch delivered = new CountDownLatch(1);
			connection.getStats(report -> delivered.countDown());

			assertTrue(delivered.await(5, TimeUnit.SECONDS));
		}
		finally {
			closeWithin(connection);
		}
	}

	@Test
	void createOfferObserverThrows() throws Exception {
		RTCPeerConnection connection = factory.createPeerConnection(new RTCConfiguration(), candidate -> { });

		try {
			connection.createDataChannel("data", new RTCDataChannelInit());
			connection.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
				@Override
				public void onSuccess(RTCSessionDescription description) {
					throw new CallbackFailure("offer");
				}

				@Override
				public void onFailure(String error) {
				}
			});

			awaitUncaught("offer");

			// The signaling thread still runs the next operation.
			TestCreateDescObserver observer = new TestCreateDescObserver();
			connection.createOffer(new RTCOfferOptions(), observer);

			assertNotNull(observer.get(5, TimeUnit.SECONDS));
		}
		finally {
			closeWithin(connection);
		}
	}

	@Test
	void peerConnectionObserverThrows() throws Exception {
		RTCPeerConnection[] peers = new RTCPeerConnection[2];
		CountDownLatch connected = new CountDownLatch(2);

		for (int i = 0; i < 2; i++) {
			int remote = 1 - i;

			peers[i] = factory.createPeerConnection(new RTCConfiguration(), new PeerConnectionObserver() {
				@Override
				public void onIceCandidate(RTCIceCandidate candidate) {
					peers[remote].addIceCandidate(candidate);

					throw new CallbackFailure("candidate");
				}

				@Override
				public void onConnectionChange(RTCPeerConnectionState state) {
					if (state == RTCPeerConnectionState.CONNECTED) {
						connected.countDown();
					}

					throw new CallbackFailure("connection");
				}
			});
		}

		try {
			peers[0].createDataChannel("data", new RTCDataChannelInit());

			TestSetDescObserver setOffer = new TestSetDescObserver();
			peers[0].setLocalDescription(setOffer);
			setOffer.get(5, TimeUnit.SECONDS);

			TestSetDescObserver setRemoteOffer = new TestSetDescObserver();
			peers[1].setRemoteDescription(peers[0].getLocalDescription(), setRemoteOffer);
			setRemoteOffer.get(5, TimeUnit.SECONDS);

			TestSetDescObserver setAnswer = new TestSetDescObserver();
			peers[1].setLocalDescription(setAnswer);
			setAnswer.get(5, TimeUnit.SECONDS);

			TestSetDescObserver setRemoteAnswer = new TestSetDescObserver();
			peers[0].setRemoteDescription(peers[1].getLocalDescription(), setRemoteAnswer);
			setRemoteAnswer.get(5, TimeUnit.SECONDS);

			assertTrue(connected.await(10, TimeUnit.SECONDS), "Peers did not connect");

			awaitUncaught("candidate");
			awaitUncaught("connection");
		}
		finally {
			// Used to hang on a lock that a thrown callback left held.
			closeWithin(peers[0]);
			closeWithin(peers[1]);
		}
	}

	@Test
	void dataChannelObserverThrows() throws Exception {
		try (TestDataChannelPair pair = new TestDataChannelPair(factory)) {
			pair.connect();

			CountDownLatch received = new CountDownLatch(2);

			pair.receiver.unregisterObserver();
			pair.receiver.registerObserver(new RTCDataChannelObserver() {
				@Override
				public void onBufferedAmountChange(long previousAmount) {
				}

				@Override
				public void onStateChange() {
				}

				@Override
				public void onMessage(RTCDataChannelBuffer buffer) {
					received.countDown();

					throw new CallbackFailure("message");
				}
			});

			// Every message arrives although the observer throws on each one.
			for (String text : new String[] { "one", "two" }) {
				ByteBuffer data = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
				pair.sender.send(new RTCDataChannelBuffer(data, false));
			}

			assertTrue(received.await(5, TimeUnit.SECONDS), "Messages were lost");

			awaitUncaught("message");
		}
	}

	private void awaitUncaught(String callback) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

		while (System.nanoTime() < deadline) {
			for (Throwable error : uncaught) {
				if (error instanceof CallbackFailure && callback.equals(error.getMessage())) {
					return;
				}
			}

			Thread.sleep(10);
		}

		throw new AssertionError("No uncaught exception from the " + callback + " callback, got " + uncaught);
	}

	private static void closeWithin(RTCPeerConnection connection) {
		assertTimeoutPreemptively(Duration.ofSeconds(10), connection::close);
	}



	private static class CallbackFailure extends RuntimeException {

		CallbackFailure(String callback) {
			super(callback);
		}
	}
}
