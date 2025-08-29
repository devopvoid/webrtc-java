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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RTCDataChannelTests extends TestBase {

	@Test
	void bufferedAmountChangeCallback() throws Exception {
		DataPeerConnection caller = new DataPeerConnection(factory);
		DataPeerConnection callee = new DataPeerConnection(factory);

		caller.setRemotePeerConnection(callee);
		callee.setRemotePeerConnection(caller);

		callee.setRemoteDescription(caller.createOffer());
		caller.setRemoteDescription(callee.createAnswer());

		caller.waitUntilConnected();
		callee.waitUntilConnected();

		// Prepare a latch-based observer to detect buffered amount change.
		CountDownLatch latch = new CountDownLatch(1);

		caller.getLocalDataChannel().registerObserver(new RTCDataChannelObserver() {
			@Override
			public void onBufferedAmountChange(long previousAmount) {
				latch.countDown();
			}

			@Override
			public void onStateChange() { }

			@Override
			public void onMessage(RTCDataChannelBuffer buffer) { }
		});

		// Send a large enough message to cause buffering (increase from 0).
		byte[] big = new byte[64 * 1024]; // 64 KB
		ByteBuffer data = ByteBuffer.wrap(big);
		RTCDataChannelBuffer buffer = new RTCDataChannelBuffer(data, true);
		caller.getLocalDataChannel().send(buffer);

		// Wait for the callback to fire to avoid flakiness.
		boolean signaled = latch.await(5, java.util.concurrent.TimeUnit.SECONDS);

		assertTrue(signaled, "onBufferedAmountChange should be called when sending data");

		caller.close();
		callee.close();
	}

	@Test
	void textMessage() throws Exception {
		DataPeerConnection caller = new DataPeerConnection(factory);
		DataPeerConnection callee = new DataPeerConnection(factory);

		caller.setRemotePeerConnection(callee);
		callee.setRemotePeerConnection(caller);

		callee.setRemoteDescription(caller.createOffer());
		caller.setRemoteDescription(callee.createAnswer());

		caller.waitUntilConnected();
		callee.waitUntilConnected();

		Thread.sleep(500);

		caller.sendTextMessage("Hello world");
		callee.sendTextMessage("Hi :)");

		Thread.sleep(500);

		assertEquals(Collections.singletonList("Hello world"), callee.getReceivedTexts());
		assertEquals(Collections.singletonList("Hi :)"), caller.getReceivedTexts());

		caller.close();
		callee.close();
	}



	private static class DataPeerConnection extends TestPeerConnection {

		private final List<String> receivedTexts = new ArrayList<>();

		private final RTCDataChannel localDataChannel;

		private RTCDataChannel remoteDataChannel;


		DataPeerConnection(PeerConnectionFactory factory) {
			super(factory);

			localDataChannel = getPeerConnection().createDataChannel("dc", new RTCDataChannelInit());
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

		RTCDataChannel getLocalDataChannel() {
			return localDataChannel;
		}

		List<String> getReceivedTexts() {
			return receivedTexts;
		}

		void sendTextMessage(String message) throws Exception {
			ByteBuffer data = ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));
			RTCDataChannelBuffer buffer = new RTCDataChannelBuffer(data, false);

			localDataChannel.send(buffer);
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

		void close() {
			if (nonNull(localDataChannel)) {
				localDataChannel.unregisterObserver();
				localDataChannel.close();
				localDataChannel.dispose();
			}
			if (nonNull(remoteDataChannel)) {
				remoteDataChannel.unregisterObserver();
				remoteDataChannel.close();
				remoteDataChannel.dispose();
			}

			super.close();
		}
	}
}
