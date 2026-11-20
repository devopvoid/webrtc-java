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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.onvoid.webrtc.RTCRtpEncodingParameters;
import dev.onvoid.webrtc.RTCRtpSender;

class SimulcastTests extends ClientTestBase {

	/** Smallest first, as LiveKit orders them. */
	private static Simulcast layers() {
		return Simulcast.builder()
				.layer("q", Encoding.builder().scaleDownBy(4).maxBitrate(150_000).build())
				.layer("h", Encoding.builder().scaleDownBy(2).maxBitrate(500_000).build())
				.layer("f", Encoding.builder().maxBitrate(1_500_000).build())
				.build();
	}

	@Test
	void builderChecksRids() {
		Encoding encoding = Encoding.builder().build();

		for (String rid : new String[] { "", "a-b", "a b", "ä", "12345678901234567" }) {
			assertThrows(IllegalArgumentException.class, () -> Simulcast.builder().layer(rid, encoding), rid);
		}

		Simulcast.Builder builder = Simulcast.builder().layer("a1", encoding);
		assertThrows(IllegalArgumentException.class, () -> builder.layer("a1", encoding));
		assertThrows(NullPointerException.class, () -> builder.layer(null, encoding));

		// As long as WebRTC takes.
		assertEquals(List.of("1234567890123456", "B"), Simulcast.builder()
				.layer("1234567890123456", encoding).layer("B", encoding).build().rids());
	}

	@Test
	void builderChecksCount() {
		Encoding encoding = Encoding.builder().build();

		assertThrows(IllegalArgumentException.class, () -> Simulcast.builder().layer("a", encoding).build());
		assertThrows(IllegalArgumentException.class, () -> Simulcast.builder().layer("a", encoding)
				.layer("b", encoding).layer("c", encoding).layer("d", encoding).build());

		assertEquals(List.of("f", "h", "q"), Simulcast.standard().rids());
	}

	@Test
	void layersOffered() throws Exception {
		Peer peer = manualPeer();
		Sender sender = peer.addTrack(rtc.media().customVideo("video"), layers());

		assertEquals(List.of("q", "h", "f"), sender.rids());

		String sdp = await(peer.negotiator().createOffer()).sdp;

		assertTrue(sdp.contains("a=rid:q send\r\n"), sdp);
		assertTrue(sdp.contains("a=rid:h send\r\n"), sdp);
		assertTrue(sdp.contains("a=rid:f send\r\n"), sdp);
		assertTrue(sdp.contains("a=simulcast:send q;h;f\r\n"), sdp);
	}

	@Test
	void layerConfigured() throws Exception {
		Peer peer = manualPeer();
		Sender sender = peer.addTrack(rtc.media().customVideo("video"), layers());

		await(sender.configure("f", Encoding.builder().active(false).maxBitrate(1_000_000).build()));

		Map<String, RTCRtpEncodingParameters> encodings = encodings(peer);
		assertEquals(List.of("q", "h", "f"), List.copyOf(encodings.keySet()));

		assertFalse(encodings.get("f").active);
		assertEquals(1_000_000, encodings.get("f").maxBitrate);
		// The other layers keep their values.
		assertTrue(encodings.get("q").active);
		assertEquals(150_000, encodings.get("q").maxBitrate);
		assertEquals(4.0, encodings.get("q").scaleResolutionDownBy);
		assertTrue(encodings.get("h").active);
		assertEquals(500_000, encodings.get("h").maxBitrate);

		// Every layer alike.
		await(sender.configure(Encoding.builder().maxFrameRate(10).build()));

		encodings(peer).values().forEach(encoding -> assertEquals(10.0, encoding.maxFramerate));
	}

	@Test
	void unknownLayerRefused() throws Exception {
		Peer peer = manualPeer();
		Sender layered = peer.addTrack(rtc.media().customVideo("layered"), layers());
		Sender single = peer.addTrack(rtc.media().customVideo("single"));
		Encoding encoding = Encoding.builder().active(false).build();

		assertThrows(IllegalArgumentException.class, () -> layered.configure("x", encoding));
		assertThrows(IllegalArgumentException.class, () -> single.configure("f", encoding));
		assertThrows(NullPointerException.class, () -> layered.configure(null, encoding));
		assertTrue(single.rids().isEmpty());

		// Nothing changed: the refusal comes before the peer is reached.
		encodings(peer).values().forEach(values -> assertTrue(values.active, values.rid));
	}

	@Test
	void audioRefused() {
		Peer peer = manualPeer();

		assertThrows(IllegalArgumentException.class,
				() -> peer.addTrack(rtc.media().customAudio("audio"), layers()));
		assertTrue(peer.senders().isEmpty());
	}

	private Peer manualPeer() {
		return rtc.createPeer(PeerOptions.builder().negotiation(Negotiation.MANUAL).build());
	}

	/**
	 * @return the encodings of the peer's layered sender, by rid, in their
	 * order, as WebRTC has them.
	 */
	private static Map<String, RTCRtpEncodingParameters> encodings(Peer peer) throws Exception {
		return await(peer.withNative(connection -> {
			Map<String, RTCRtpEncodingParameters> byRid = new LinkedHashMap<>();
			RTCRtpSender[] senders = connection.getSenders();

			try {
				for (RTCRtpSender sender : senders) {
					for (RTCRtpEncodingParameters encoding : sender.getParameters().encodings) {
						if (encoding.rid != null) {
							byRid.put(encoding.rid, encoding);
						}
					}
				}
			}
			finally {
				for (RTCRtpSender sender : senders) {
					sender.dispose();
				}
			}

			return byRid;
		}));
	}

}
