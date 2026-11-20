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

package dev.onvoid.webrtc.livekit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.junit.jupiter.api.Test;

import dev.onvoid.webrtc.RTCIceCandidate;

class SignalingFormatTests {

	private static final String CANDIDATE =
			"candidate:1 1 udp 2122260223 192.168.1.2 50000 typ host generation 0";

	@Test
	void candidateRoundTrips() {
		RTCIceCandidate candidate = new RTCIceCandidate("0", 1, CANDIDATE);

		RTCIceCandidate decoded = CandidateJson.decode(CandidateJson.encode(candidate));

		assertEquals("0", decoded.sdpMid);
		assertEquals(1, decoded.sdpMLineIndex);
		assertEquals(CANDIDATE, decoded.sdp);
	}

	@Test
	void candidateOfServerParses() {
		// As pion's ICECandidateInit marshals it.
		String json = "{\"candidate\":\"" + CANDIDATE + "\",\"sdpMid\":\"2\",\"sdpMLineIndex\":2,"
				+ "\"usernameFragment\":null}";

		RTCIceCandidate decoded = CandidateJson.decode(json);

		assertEquals("2", decoded.sdpMid);
		assertEquals(2, decoded.sdpMLineIndex);
		assertEquals(CANDIDATE, decoded.sdp);
	}

	@Test
	void candidateWithoutMidParses() {
		RTCIceCandidate decoded = CandidateJson.decode(" { \"candidate\" : \"a\\\"b\\u0041\" } ");

		assertNull(decoded.sdpMid);
		assertEquals(0, decoded.sdpMLineIndex);
		assertEquals("a\"bA", decoded.sdp);
	}

	@Test
	void candidateWithoutCandidateFails() {
		assertThrows(IllegalArgumentException.class, () -> CandidateJson.decode("{\"sdpMid\":\"0\"}"));
		assertThrows(IllegalArgumentException.class, () -> CandidateJson.decode("{\"candidate\":"));
	}

	@Test
	void rtcUriOfServerUrl() {
		URI uri = SignalClient.rtcUri(URI.create("https://live.example.com/"), 16, true);

		assertEquals("wss", uri.getScheme());
		assertEquals("live.example.com", uri.getHost());
		assertEquals("/rtc", uri.getPath());
		assertTrue(uri.getQuery().contains("protocol=16"), uri::toString);
		assertTrue(uri.getQuery().contains("auto_subscribe=1"), uri::toString);
	}

	@Test
	void rtcUriKeepsPortAndPath() {
		URI uri = SignalClient.rtcUri(URI.create("ws://localhost:7880/lk"), 16, false);

		assertEquals("ws://localhost:7880/lk/rtc", uri.toString().substring(0, uri.toString().indexOf('?')));
		assertTrue(uri.getQuery().contains("auto_subscribe=0"), uri::toString);
	}

	@Test
	void rtcUriRefusesOtherSchemes() {
		assertThrows(IllegalArgumentException.class,
				() -> SignalClient.rtcUri(URI.create("ftp://example.com"), 16, true));
	}

}
