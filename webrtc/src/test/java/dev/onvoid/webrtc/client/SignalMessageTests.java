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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class SignalMessageTests {

	private static final String SDP = "v=0\r\no=- 4611731400430051336 2 IN IP4 127.0.0.1\r\ns=\"quoted\" \\ \u0001\r\n";

	/** A candidate message, with its sdpMLineIndex left to fill in. */
	private static final String CANDIDATE =
			"{\"candidate\":\"candidate:1 1 udp 1 10.0.0.1 9 typ host\",\"sdpMid\":\"0\",\"sdpMLineIndex\":%s}";

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

	@TestFactory
	List<DynamicTest> invalidMLineIndexRejected() {
		// Index, then the start of the message it fails with. The parser
		// checks the syntax and that a number is finite, the message that it
		// is an integer in range.
		String[] cases = {
				"1.9", "\"sdpMLineIndex\" is not an integer: 1.9",
				"0.5", "\"sdpMLineIndex\" is not an integer",
				"-0.5", "\"sdpMLineIndex\" is not an integer",
				"1.0000001", "\"sdpMLineIndex\" is not an integer",
				"-1", "\"sdpMLineIndex\" is not from 0 to 65535: -1",
				"65536", "\"sdpMLineIndex\" is not from 0 to 65535: 65536",
				"4294967296", "\"sdpMLineIndex\" is not from 0 to 65535: 4294967296",
				"1e300", "\"sdpMLineIndex\" is not from 0 to 65535: 1.0E300",
				"1e999", "Number out of range",
				"01", "Leading zero in a number",
				"1.", "Expected a digit after the decimal point",
				"\"1\"", "\"sdpMLineIndex\" is not a number",
				"true", "\"sdpMLineIndex\" is not a number",
				"[0]", "\"sdpMLineIndex\" is not a number"
		};
		List<DynamicTest> tests = new ArrayList<>();

		for (int i = 0; i < cases.length; i += 2) {
			String index = cases[i];
			String message = cases[i + 1];

			tests.add(DynamicTest.dynamicTest(index, () -> {
				IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
						() -> SignalMessage.fromJson(String.format(CANDIDATE, index)));

				assertTrue(e.getMessage().startsWith(message), e.getMessage());
			}));
		}

		return tests;
	}

	@TestFactory
	List<DynamicTest> validMLineIndexAccepted() {
		String[] indexes = { "0", "-0", "65535", "1.0", "1e2", "2E+1", "1000e-1" };
		int[] expected = { 0, 0, 65535, 1, 100, 20, 100 };
		List<DynamicTest> tests = new ArrayList<>();

		for (int i = 0; i < indexes.length; i++) {
			String index = indexes[i];
			int value = expected[i];

			tests.add(DynamicTest.dynamicTest(index, () -> assertEquals(value,
					SignalMessage.fromJson(String.format(CANDIDATE, index)).candidate().sdpMLineIndex)));
		}

		return tests;
	}

	@Test
	void strictJsonRequired() {
		// A browser's JSON.stringify escapes the line breaks of an SDP.
		IllegalArgumentException rawBreak = assertThrows(IllegalArgumentException.class,
				() -> SignalMessage.fromJson("{\"type\":\"offer\",\"sdp\":\"v=0\r\n\"}"));
		// Which of the two counts would depend on the parser.
		IllegalArgumentException twoTypes = assertThrows(IllegalArgumentException.class,
				() -> SignalMessage.fromJson("{\"type\":\"offer\",\"type\":\"answer\",\"sdp\":\"v=0\\r\\n\"}"));

		assertTrue(rawBreak.getMessage().startsWith("Unescaped control character U+000D"), rawBreak.getMessage());
		assertTrue(twoTypes.getMessage().startsWith("Duplicate member name \"type\""), twoTypes.getMessage());
	}

	@Test
	void unreadableIndexNotWritten() {
		// A removed candidate has the index -1, which fromJson would reject.
		assertThrows(IllegalArgumentException.class,
				() -> SignalMessage.of(new RTCIceCandidate("0", -1, "candidate:1 1 udp 1 10.0.0.1 9 typ host")));
		assertThrows(IllegalArgumentException.class,
				() -> SignalMessage.of(new RTCIceCandidate("0", 65536, "candidate:1 1 udp 1 10.0.0.1 9 typ host")));
		assertEquals(65535, SignalMessage.fromJson(SignalMessage.of(
				new RTCIceCandidate("0", 65535, "candidate:1 1 udp 1 10.0.0.1 9 typ host")).toJson())
				.candidate().sdpMLineIndex);
	}

	@Test
	void deepNestingRejected() {
		// In a member the message does not know, which is read all the same.
		// Without a limit, 10,000 levels overflow the stack.
		String message = "{\"type\":\"answer\",\"sdp\":\"v=0\\r\\n\",\"extra\":%s}";

		assertEquals(SignalMessage.Type.ANSWER,
				SignalMessage.fromJson(String.format(message, nested(Json.MAX_DEPTH - 1))).type());
		assertThrows(IllegalArgumentException.class,
				() -> SignalMessage.fromJson(String.format(message, nested(Json.MAX_DEPTH))));
		assertThrows(IllegalArgumentException.class,
				() -> SignalMessage.fromJson(String.format(message, nested(10_000))));
	}

	@Test
	void oversizedMessageRejected() {
		char[] sdp = new char[Json.MAX_LENGTH];
		Arrays.fill(sdp, 'a');

		assertThrows(IllegalArgumentException.class,
				() -> SignalMessage.fromJson("{\"type\":\"offer\",\"sdp\":\"" + new String(sdp) + "\"}"));
	}

	/**
	 * @return arrays nested the given number of levels deep.
	 */
	private static String nested(int levels) {
		StringBuilder out = new StringBuilder();

		for (int i = 0; i < levels; i++) {
			out.append('[');
		}
		for (int i = 0; i < levels; i++) {
			out.append(']');
		}

		return out.toString();
	}

}
