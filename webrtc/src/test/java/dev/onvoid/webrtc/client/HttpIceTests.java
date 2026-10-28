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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import dev.onvoid.webrtc.RTCIceServer;

import org.junit.jupiter.api.Test;

/**
 * What a WHIP or WHEP session reads and writes about ICE, without WebRTC.
 */
class HttpIceTests {

	private static final String OFFER = String.join("\r\n",
			"v=0",
			"o=- 1 2 IN IP4 127.0.0.1",
			"s=-",
			"t=0 0",
			"a=group:BUNDLE 0 1",
			"a=msid-semantic: WMS",
			"m=audio 9 UDP/TLS/RTP/SAVPF 111",
			"c=IN IP4 0.0.0.0",
			"a=ice-ufrag:aaaa",
			"a=ice-pwd:aaaaaaaaaaaaaaaaaaaaaaaa",
			"a=ice-options:trickle",
			"a=mid:0",
			"a=recvonly",
			"m=video 9 UDP/TLS/RTP/SAVPF 96",
			"c=IN IP4 0.0.0.0",
			"a=ice-ufrag:aaaa",
			"a=ice-pwd:aaaaaaaaaaaaaaaaaaaaaaaa",
			"a=ice-options:trickle",
			"a=mid:1",
			"a=recvonly",
			"");

	private static final String ANSWER = OFFER
			.replace("aaaa", "bbbb")
			.replace("a=mid:0\r\n", "a=mid:0\r\na=candidate:1 1 udp 2122260223 192.0.2.1 5000 typ host\r\n"
					+ "a=end-of-candidates\r\n");


	@Test
	void linksRead() {
		List<RTCIceServer> servers = HttpIce.iceServers(Arrays.asList(
				"<stun:stun.example.net>; rel=\"ice-server\"",
				"<turn:turn.example.net?transport=udp>; rel=\"ice-server\"; username=\"user\"; "
						+ "credential=\"my, \\\"password\\\"\", <https://example.net/terms>; rel=\"terms-of-service\"",
				"<turns:turn.example.net?transport=tcp>; rel=\"ice-server other\"; username=user; credential=pw"));

		assertEquals(3, servers.size(), () -> "Servers: " + servers);
		assertEquals(Arrays.asList("stun:stun.example.net"), servers.get(0).urls);
		assertNull(servers.get(0).username);
		assertEquals(Arrays.asList("turn:turn.example.net?transport=udp"), servers.get(1).urls);
		assertEquals("user", servers.get(1).username);
		// A comma and escaped quotes within the quoted value end nothing.
		assertEquals("my, \"password\"", servers.get(1).password);
		assertEquals(Arrays.asList("turns:turn.example.net?transport=tcp"), servers.get(2).urls);
		assertEquals("pw", servers.get(2).password);
	}

	@Test
	void fragmentOfOffer() {
		// The m-line that tags the BUNDLE group stands for all, as RFC 9725
		// asks with max-bundle.
		HttpIce.Fragment fragment = HttpIce.Fragment.of(OFFER);

		assertEquals(String.join("\r\n",
				"a=ice-options:trickle",
				"a=group:BUNDLE 0 1",
				"m=audio 9 UDP/TLS/RTP/SAVPF 111",
				"a=mid:0",
				"a=ice-ufrag:aaaa",
				"a=ice-pwd:aaaaaaaaaaaaaaaaaaaaaaaa",
				""), fragment.text);
		assertEquals("aaaa", fragment.ufrag);
	}

	@Test
	void fragmentOwnsItsCandidates() {
		HttpIce.Fragment fragment = HttpIce.Fragment.of(OFFER);

		assertTrue(fragment.owns("candidate:1 1 udp 2122260223 192.0.2.1 5000 typ host generation 0 ufrag aaaa"));
		assertFalse(fragment.owns("candidate:1 1 udp 2122260223 192.0.2.1 5000 typ host generation 0 ufrag zzzz"));
		assertTrue(fragment.owns("candidate:1 1 udp 2122260223 192.0.2.1 5000 typ host"));
	}

	@Test
	void restartAppliedToAnswer() {
		// The session's new ICE information replaces the old in every m-line,
		// and its candidates replace the answer's, in the tagged m-line.
		HttpIce.Fragment restart = HttpIce.Fragment.parse(String.join("\r\n",
				"a=ice-lite",
				"a=group:BUNDLE 0 1",
				"m=audio 9 UDP/TLS/RTP/SAVPF 111",
				"a=mid:0",
				"a=ice-ufrag:cccc",
				"a=ice-pwd:cccccccccccccccccccccccc",
				"a=candidate:2 1 udp 2122260223 192.0.2.2 6000 typ host",
				"a=end-of-candidates",
				""));
		String answer = restart.applyTo(ANSWER);

		assertFalse(answer.contains("bbbb"), answer);
		assertEquals(2, answer.split("a=ice-ufrag:cccc", -1).length - 1, answer);
		assertFalse(answer.contains("192.0.2.1"), answer);
		assertTrue(answer.contains("a=mid:0\r\n"), answer);
		assertTrue(answer.indexOf("192.0.2.2") < answer.indexOf("m=video"), answer);
		assertTrue(answer.contains("a=end-of-candidates"), answer);
	}

	@Test
	void localCredentialsKeepCandidates() {
		HttpIce.Fragment restart = HttpIce.Fragment.parse("a=ice-ufrag:cccc\r\na=ice-pwd:cccccccccccccccccccccccc\r\n");
		String answer = restart.applyCredentialsTo(ANSWER);
		assertTrue(answer.contains("192.0.2.1"), answer);
		assertTrue(answer.contains("a=end-of-candidates"), answer);
		assertTrue(answer.contains("a=ice-ufrag:cccc"), answer);
	}

	@Test
	void restartWithoutCandidatesClearsThem() {
		HttpIce.Fragment restart = HttpIce.Fragment.parse("a=ice-ufrag:cccc\r\na=ice-pwd:cccccccccccccccccccccccc\r\n");
		String answer = restart.applyTo(ANSWER);

		assertFalse(answer.contains("192.0.2.1"), answer);
		assertFalse(answer.contains("a=end-of-candidates"), answer);
		assertTrue(answer.contains("a=ice-ufrag:cccc"), answer);
		String ended = HttpIce.Fragment.parse(restart.text + "a=end-of-candidates\r\n").applyTo(ANSWER);
		assertFalse(ended.contains("a=candidate:"), ended);
		assertTrue(ended.contains("a=end-of-candidates"), ended);
	}
}
