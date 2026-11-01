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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.onvoid.webrtc.media.MediaType;
import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Strings that go to native code and come back. They used to come back
 * through NewStringUTF, which takes Modified UTF-8 rather than the UTF-8 they
 * were sent as, and empty ones came back as null.
 */
class NativeStringTests extends TestBase {

	/** Each must come back exactly as it was sent. */
	private static final List<String> VALUES = List.of(
			"",
			"test-😀",                 // "test-😀", 7 chars.
			"😀",
			"a\u0000b",
			"\u0000",
			"end\u0000",
			"äöü € 中文 𝄞 👍🏽",
			"\u007F\u0080߿ࠀ￿𐀀􏿿");

	private RTCPeerConnection connection;


	@BeforeEach
	void init() {
		connection = factory.createPeerConnection(new RTCConfiguration(), candidate -> { });
	}

	@AfterEach
	void dispose() {
		connection.close();
	}

	@Test
	void dataChannelLabelAndProtocol() {
		for (String value : VALUES) {
			RTCDataChannelInit init = new RTCDataChannelInit();
			init.protocol = value;

			RTCDataChannel channel = connection.createDataChannel(value, init);

			try {
				assertSame(value, channel.getLabel(), "label");
				assertSame(value, channel.getProtocol(), "protocol");
			}
			finally {
				channel.dispose();
			}
		}
	}

	@Test
	void trackAndStreamIds() {
		AudioTrackSource source = factory.createAudioSource(new AudioOptions());

		try {
			for (String value : VALUES) {
				AudioTrack track = factory.createAudioTrack(value, source);

				try {
					assertSame(value, track.getId(), "track id");

					if (value.isEmpty()) {
						// An empty stream id names no stream.
						continue;
					}

					RTCRtpSender sender = connection.addTrack(track, List.of(value));

					assertEquals(List.of(value), sender.getStreams());

					connection.removeTrack(sender);
				}
				finally {
					track.dispose();
				}
			}
		}
		finally {
			source.dispose();
		}
	}

	@Test
	void unpairedSurrogateBecomesQuestionMark() {
		// UTF-8 has no form for it, so it goes the way of String.getBytes(UTF_8).
		RTCDataChannel channel = connection.createDataChannel("x\uD800y\uDC00z", new RTCDataChannelInit());

		try {
			assertEquals("x?y?z", channel.getLabel());
		}
		finally {
			channel.dispose();
		}
	}

	@Test
	void iceServerCredentials() {
		RTCIceServer server = new RTCIceServer();
		server.urls.add("turn:127.0.0.1:3478");
		server.username = "nutzer-😀";
		server.password = "a\u0000b";

		RTCIceServer unset = new RTCIceServer();
		unset.urls.add("stun:127.0.0.1:3478");

		RTCConfiguration config = new RTCConfiguration();
		config.iceServers.add(server);
		config.iceServers.add(unset);

		RTCPeerConnection peer = factory.createPeerConnection(config, candidate -> { });

		try {
			List<RTCIceServer> servers = peer.getConfiguration().iceServers;

			assertSame(server.username, servers.get(0).username, "username");
			assertSame(server.password, servers.get(0).password, "password");

			// WebRTC keeps an unset credential as an empty string; it stays unset.
			assertNull(servers.get(1).username);
			assertNull(servers.get(1).password);
			assertNull(servers.get(1).hostname);
		}
		finally {
			peer.close();
		}
	}

	@Test
	void errorMessageCarriesTrackId() {
		// WebRTC puts the id into its error text, which used to go to vsnprintf
		// as the format, and on to NewStringUTF.
		String id = "track %s%n%d ä😀";

		AudioTrackSource source = factory.createAudioSource(new AudioOptions());
		AudioTrack track = factory.createAudioTrack(id, source);

		try {
			RTCRtpSender sender = connection.addTrack(track, List.of("stream"));

			try {
				RuntimeException e = assertThrows(RuntimeException.class,
						() -> connection.addTrack(track, List.of("stream")));

				assertTrue(e.getMessage().contains(id), () -> "message: " + codeUnits(e.getMessage()));
			}
			finally {
				connection.removeTrack(sender);
			}
		}
		finally {
			track.dispose();
			source.dispose();
		}
	}

	@Test
	void errorMessageCarriesSdpLine() {
		// The parse error quotes the line, and the native exception reaches
		// Java through the same path as above.
		String line = "%s%n ü😀";
		RTCSessionDescription description = new RTCSessionDescription(RTCSdpType.OFFER,
				"v=0\r\n" + line + "\r\n");

		Throwable e = assertThrows(Throwable.class,
				() -> connection.setRemoteDescription(description, new SetSessionDescriptionObserver() {

					@Override
					public void onSuccess() {
					}

					@Override
					public void onFailure(String error) {
					}
				}));

		assertTrue(e.getMessage().contains(line), () -> "message: " + codeUnits(e.getMessage()));
	}

	@Test
	void midIsNullUntilNegotiated() {
		RTCRtpTransceiver transceiver = connection.addTransceiver(MediaType.AUDIO, new RTCRtpTransceiverInit());

		assertNull(transceiver.getMid());
	}

	/**
	 * Equal content, with the code units in the message: two strings that
	 * differ only in a NUL or a surrogate look alike when printed.
	 */
	private static void assertSame(String expected, String actual, String what) {
		assertEquals(expected, actual, () -> what + ": expected " + codeUnits(expected)
				+ " but was " + codeUnits(actual));
	}

	private static String codeUnits(String value) {
		if (value == null) {
			return "null";
		}

		StringBuilder builder = new StringBuilder("[");

		for (int i = 0; i < value.length(); i++) {
			builder.append(i == 0 ? "" : " ").append(String.format("%04X", (int) value.charAt(i)));
		}

		return builder.append("] (").append(value.length()).append(" chars)").toString();
	}
}
