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

package dev.onvoid.webrtc.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class TimeoutsTests {

	@Test
	void onlyZeroStaysZero() {
		assertEquals(0, Timeouts.toMillis(Duration.ZERO));
		assertEquals(0, Timeouts.toMicros(Duration.ZERO));

		// Rounded down, these would be no limit at all to many waits.
		assertEquals(1, Timeouts.toMillis(Duration.ofNanos(1)));
		assertEquals(1, Timeouts.toMicros(Duration.ofNanos(1)));
		assertEquals(1, Timeouts.toMillis(Duration.ofNanos(999_999)));
	}

	@Test
	void roundsUp() {
		assertEquals(2, Timeouts.toMillis(Duration.ofNanos(1_000_001)));
		assertEquals(1_001, Timeouts.toMillis(Duration.ofSeconds(1, 1)));
		assertEquals(1_000_001, Timeouts.toMicros(Duration.ofSeconds(1, 1)));

		// Whole units stay as they are.
		assertEquals(1_500, Timeouts.toMillis(Duration.ofMillis(1_500)));
		assertEquals(1_500, Timeouts.toMicros(Duration.ofNanos(1_500_000)));
	}

	@Test
	void saturates() {
		// Exactly the largest count, and the next nanosecond beyond it.
		assertEquals(Long.MAX_VALUE, Timeouts.toMillis(Duration.ofMillis(Long.MAX_VALUE)));
		assertEquals(Long.MAX_VALUE, Timeouts.toMillis(Duration.ofMillis(Long.MAX_VALUE).plusNanos(1)));
		assertEquals(Long.MAX_VALUE, Timeouts.toMicros(Duration.ofNanos(Long.MAX_VALUE).multipliedBy(1_000)));

		// Longer than nanoseconds can count, which once came out as zero.
		assertEquals(Long.MAX_VALUE, Timeouts.toMicros(Duration.ofSeconds(Long.MAX_VALUE, 999_999_999)));
		assertEquals(Long.MAX_VALUE, Timeouts.toMillis(Duration.ofSeconds(Long.MAX_VALUE)));
	}

	@Test
	void rejectsNegative() {
		Duration negative = Duration.ofNanos(-1);

		assertThrows(IllegalArgumentException.class, () -> Timeouts.toMillis(negative));
		assertThrows(IllegalArgumentException.class, () -> Timeouts.toMicros(negative));
		assertThrows(IllegalArgumentException.class, () -> Timeouts.requireNonNegative(negative, "timeout"));
		assertThrows(IllegalArgumentException.class, () -> Timeouts.requirePositive(negative, "timeout"));
		assertThrows(NullPointerException.class, () -> Timeouts.requirePositive(null, "timeout"));
		assertThrows(NullPointerException.class, () -> Timeouts.requireNonNegative(null, "timeout"));
	}

	@Test
	void zeroIsUnlimitedOnlyWhereAllowed() {
		assertThrows(IllegalArgumentException.class, () -> Timeouts.requirePositive(Duration.ZERO, "timeout"));
		assertSame(Duration.ZERO, Timeouts.requireNonNegative(Duration.ZERO, "timeout"));

		Duration tiny = Duration.ofNanos(1);

		assertSame(tiny, Timeouts.requirePositive(tiny, "timeout"));
		assertSame(tiny, Timeouts.requireNonNegative(tiny, "timeout"));
	}

}
