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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Tests the SCTP transport that carries the data channels of a connection.
 */
@Execution(ExecutionMode.SAME_THREAD)
class RTCSctpTransportTests extends TestBase {

	@Test
	void noneWithoutDataChannels() {
		RTCPeerConnection connection = factory.createPeerConnection(new RTCConfiguration(), candidate -> { });

		try {
			assertNull(connection.getSctpTransport());
		}
		finally {
			connection.close();
		}
	}

	@Test
	void unconnectedTransport() throws Exception {
		RTCPeerConnection connection = factory.createPeerConnection(new RTCConfiguration(), candidate -> { });
		RTCDataChannel channel = connection.createDataChannel("data", new RTCDataChannelInit());

		try {
			// Not before the offer is applied.
			assertNull(connection.getSctpTransport());

			TestCreateDescObserver offer = new TestCreateDescObserver();
			connection.createOffer(new RTCOfferOptions(), offer);

			TestSetDescObserver applied = new TestSetDescObserver();
			connection.setLocalDescription(offer.get(5, TimeUnit.SECONDS), applied);
			applied.get(5, TimeUnit.SECONDS);

			RTCSctpTransport transport = connection.getSctpTransport();

			try {
				assertNotNull(transport);
				RTCSctpTransportState state = transport.getState();

				// The association starts once the offer is applied.
				assertTrue(state == RTCSctpTransportState.NEW || state == RTCSctpTransportState.CONNECTING,
						"state: " + state);
				assertNull(transport.getMaxChannels());
			}
			finally {
				transport.dispose();
			}
		}
		finally {
			channel.dispose();
			connection.close();
		}
	}

	@Test
	void connectedTransport() throws Exception {
		try (TestDataChannelPair pair = new TestDataChannelPair(factory)) {
			pair.connect();

			RTCSctpTransport transport = pair.getCaller().getSctpTransport();

			try {
				assertEquals(RTCSctpTransportState.CONNECTED, transport.getState());

				// What both WebRTC peers announce in their descriptions.
				assertEquals(262144.0, transport.getMaxMessageSize());
				assertTrue(transport.getMaxChannels() > 0, "channels: " + transport.getMaxChannels());

				RTCDtlsTransport dtls = transport.getTransport();

				try {
					assertEquals(RTCDtlsTransportState.CONNECTED, dtls.getState());
				}
				finally {
					dtls.dispose();
				}
			}
			finally {
				transport.dispose();
			}
		}
	}

	@Test
	void instancesDisposeIndependently() throws Exception {
		try (TestDataChannelPair pair = new TestDataChannelPair(factory)) {
			pair.connect();

			RTCSctpTransport first = pair.getCaller().getSctpTransport();
			RTCSctpTransport second = pair.getCaller().getSctpTransport();

			assertNotSame(first, second);
			assertEquals(first, second);
			assertEquals(first.hashCode(), second.hashCode());

			first.dispose();

			assertThrows(IllegalStateException.class, first::getState);
			assertEquals(RTCSctpTransportState.CONNECTED, second.getState());

			second.dispose();
		}
	}

}
