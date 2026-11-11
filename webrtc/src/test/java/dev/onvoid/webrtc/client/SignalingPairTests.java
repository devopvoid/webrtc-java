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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;

import org.junit.jupiter.api.Test;

class SignalingPairTests {

	private static final SignalMessage OFFER =
			SignalMessage.of(new RTCSessionDescription(RTCSdpType.OFFER, "v=0\r\n"));

	private static final SignalMessage END = SignalMessage.endOfCandidates();

	private final SignalingPair link = Signaling.loopback();


	@Test
	void detachedEndHoldsForNextPeer() {
		List<SignalMessage> closed = new ArrayList<>();
		List<SignalMessage> next = new ArrayList<>();

		Subscription subscription = link.first().attach(closed::add);
		link.second().send(OFFER);
		subscription.close();
		link.second().send(END);
		link.first().attach(next::add);

		assertEquals(Collections.singletonList(OFFER), closed);
		assertEquals(Collections.singletonList(END), next);
	}

	@Test
	void staleCloseKeepsNextPeer() {
		List<SignalMessage> next = new ArrayList<>();

		Subscription stale = link.first().attach(message -> { });
		stale.close();
		link.first().attach(next::add);

		// Closed again, the first registration leaves the second in place.
		stale.close();
		link.second().send(OFFER);
		link.second().send(END);

		assertEquals(Arrays.asList(OFFER, END), next);
	}

	@Test
	void oneAttachedPeerAtATime() {
		link.first().attach(message -> { });

		assertThrows(IllegalStateException.class, () -> link.first().attach(message -> { }));
	}

}
