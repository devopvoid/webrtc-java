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

package dev.onvoid.webrtc.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;

import org.junit.jupiter.api.Test;

class SignalMessageTests {

	private static final String SDP = "v=0\r\no=- 4611731400430051336 2 IN IP4 127.0.0.1\r\ns=\"quoted\" \\ \u0001\r\n";

	@Test
	void descriptionRoundTrip() {
		SignalMessage offer = SignalMessage.of(new RTCSessionDescription(RTCSdpType.OFFER, SDP));
		SignalMessage read = SignalMessage.fromJson(offer.toJson());

		assertEquals(SignalMessage.Type.OFFER, read.type());
		assertEquals(SDP, read.description().sdp);
		assertEquals(RTCSdpType.OFFER, read.description().sdpType);
		assertEquals(offer, read);
	}

	@Test
	void candidateRoundTrip() {
		RTCIceCandidate candidate = new RTCIceCandidate("0", 1,
				"candidate:842163049 1 udp 1677729535 203.0.113.7 49203 typ srflx raddr 0.0.0.0 rport 0");
		SignalMessage read = SignalMessage.fromJson(SignalMessage.of(candidate).toJson());

		assertEquals(SignalMessage.Type.CANDIDATE, read.type());
		assertEquals("0", read.candidate().sdpMid);
		assertEquals(1, read.candidate().sdpMLineIndex);
		assertEquals(candidate.sdp, read.candidate().sdp);
	}

	@Test
	void browserShapes() {
		// What a browser's JSON.stringify writes for the perfect negotiation examples.
		SignalMessage description = SignalMessage.fromJson(
				"{ \"description\": { \"type\": \"answer\", \"sdp\": \"v=0\\r\\n\" } }");
		SignalMessage candidate = SignalMessage.fromJson("{\"candidate\":{\"candidate\":\"candidate:1 1 udp 1 10.0.0.1 9 typ host\","
				+ "\"sdpMid\":\"1\",\"sdpMLineIndex\":null,\"usernameFragment\":\"abcd\"}}");

		assertEquals(SignalMessage.Type.ANSWER, description.type());
		assertEquals("v=0\r\n", description.description().sdp);
		assertEquals("1", candidate.candidate().sdpMid);
		assertEquals(SignalMessage.Type.END_OF_CANDIDATES, SignalMessage.fromJson("{\"candidate\":null}").type());
		assertEquals(SignalMessage.Type.END_OF_CANDIDATES,
				SignalMessage.fromJson("{\"candidate\":\"\",\"sdpMid\":\"0\",\"sdpMLineIndex\":0}").type());
		assertEquals("{\"candidate\":\"\"}", SignalMessage.endOfCandidates().toJson());
		assertNull(SignalMessage.endOfCandidates().candidate());
	}

	@Test
	void invalidMessagesRejected() {
		assertThrows(IllegalArgumentException.class, () -> SignalMessage.fromJson("[]"));
		assertThrows(IllegalArgumentException.class, () -> SignalMessage.fromJson("{\"type\":\"offer\""));
		assertThrows(IllegalArgumentException.class, () -> SignalMessage.fromJson("{\"type\":\"rollback\",\"sdp\":\"\"}"));
		assertThrows(IllegalArgumentException.class, () -> SignalMessage.fromJson("{\"candidate\":\"candidate:1\"}"));
		assertThrows(IllegalArgumentException.class, () -> SignalMessage.fromJson("{\"hello\":1}"));
		assertThrows(IllegalArgumentException.class,
				() -> SignalMessage.of(new RTCSessionDescription(RTCSdpType.ROLLBACK, "")));
	}

}
