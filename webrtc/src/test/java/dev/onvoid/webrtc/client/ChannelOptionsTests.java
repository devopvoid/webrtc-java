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

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ChannelOptionsTests {

	@Test
	void lifetimeBounds() {
		for (Duration invalid : new Duration[] { Duration.ofNanos(-1), Duration.ofMillis(-1),
				Duration.ofMillis(65535).plusNanos(1), Duration.ofMillis(120000),
				Duration.ofSeconds(Long.MAX_VALUE) }) {
			assertThrows(IllegalArgumentException.class,
					() -> ChannelOptions.builder().maxPacketLifeTime(invalid));
		}
		assertThrows(NullPointerException.class, () -> ChannelOptions.builder().maxPacketLifeTime(null));
		for (int millis : new int[] { 0, 1, 65535 }) {
			assertEquals(millis, ChannelOptions.builder().maxPacketLifeTime(Duration.ofMillis(millis))
					.build().toInit().maxPacketLifeTime);
		}
	}

	@Test
	void lifetimeRoundsDown() {
		assertEquals(0, ChannelOptions.builder().maxPacketLifeTime(Duration.ofNanos(1))
				.build().toInit().maxPacketLifeTime);
		assertEquals(1, ChannelOptions.builder().maxPacketLifeTime(Duration.ofNanos(1999999))
				.build().toInit().maxPacketLifeTime);
	}

	@Test
	void retransmitBounds() {
		for (int invalid : new int[] { -1, 65536, 120000, Integer.MAX_VALUE }) {
			assertThrows(IllegalArgumentException.class, () -> ChannelOptions.builder().maxRetransmits(invalid));
		}
		for (int count : new int[] { 0, 1, 65535 }) {
			assertEquals(count, ChannelOptions.builder().maxRetransmits(count).build().toInit().maxRetransmits);
		}
		assertThrows(IllegalStateException.class, () -> ChannelOptions.builder()
				.maxRetransmits(0).maxPacketLifeTime(Duration.ZERO).build());
	}
}
