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

import static java.util.Objects.requireNonNull;

import java.time.Duration;

/**
 * Checks timeouts and converts them to the unit they are waited for in.
 * <p>
 * A conversion rounds up and saturates. A positive timeout never becomes zero,
 * which many waits, such as those of {@code HttpURLConnection} and of FFmpeg,
 * take as no limit at all; and one too long for the unit becomes the longest
 * the unit can count, rather than failing or wrapping around. So only a
 * timeout given as zero comes out as zero, and whether that means no limit is
 * for the caller to say.
 *
 * @author Alex Andres
 */
public final class Timeouts {

	private Timeouts() {
	}

	/**
	 * @param timeout the timeout to check.
	 * @param name    what the timeout is called in the message.
	 *
	 * @return the timeout.
	 *
	 * @throws IllegalArgumentException if the timeout is zero or negative.
	 */
	public static Duration requirePositive(Duration timeout, String name) {
		requireNonNull(timeout, name);

		if (timeout.isNegative() || timeout.isZero()) {
			throw new IllegalArgumentException("The " + name + " must be positive, got " + timeout);
		}

		return timeout;
	}

	/**
	 * Checks a timeout for which zero means waiting without limit.
	 *
	 * @param timeout the timeout to check.
	 * @param name    what the timeout is called in the message.
	 *
	 * @return the timeout.
	 *
	 * @throws IllegalArgumentException if the timeout is negative.
	 */
	public static Duration requireNonNegative(Duration timeout, String name) {
		requireNonNull(timeout, name);

		if (timeout.isNegative()) {
			throw new IllegalArgumentException("The " + name + " must not be negative, got " + timeout);
		}

		return timeout;
	}

	/**
	 * @param timeout a timeout that is not negative.
	 *
	 * @return the timeout in milliseconds, rounded up, or {@link
	 * Long#MAX_VALUE} if it is longer.
	 */
	public static long toMillis(Duration timeout) {
		return ceil(timeout, 1_000_000);
	}

	/**
	 * @param timeout a timeout that is not negative.
	 *
	 * @return the timeout in microseconds, rounded up, or {@link
	 * Long#MAX_VALUE} if it is longer.
	 */
	public static long toMicros(Duration timeout) {
		return ceil(timeout, 1_000);
	}

	private static long ceil(Duration timeout, long unitNanos) {
		if (timeout.isNegative()) {
			throw new IllegalArgumentException("The timeout must not be negative, got " + timeout);
		}

		long perSecond = 1_000_000_000 / unitNanos;
		long fraction = (timeout.getNano() + unitNanos - 1) / unitNanos;
		long seconds = timeout.getSeconds();

		if (seconds > (Long.MAX_VALUE - fraction) / perSecond) {
			return Long.MAX_VALUE;
		}

		return seconds * perSecond + fraction;
	}

}
