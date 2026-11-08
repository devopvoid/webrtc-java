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

package dev.onvoid.webrtc.e2ee;

/**
 * The counters seen of one KID, after the anti-replay window of SRTP (RFC
 * 3711, Section 3.3.2) that RFC 9605, Section 9.3 suggests: a counter is
 * accepted once, and only if it is newer than the highest one seen or at
 * most a window's size older.
 * <p>
 * A counter is only marked as seen once its frame authenticated, so that
 * forged frames cannot move the window.
 */
final class ReplayWindow {

	private final int size;

	/** One bit per counter of the window, indexed by counter modulo size. */
	private final long[] seen;

	/** The highest counter accepted, unsigned; meaningless while empty. */
	private long highest;

	private boolean empty = true;


	/**
	 * @param size The number of counters below the highest one that are
	 *             still accepted, a multiple of 64.
	 */
	ReplayWindow(int size) {
		this.size = size;
		this.seen = new long[size / 64];
	}

	/**
	 * @return Whether a frame with the counter may be decrypted.
	 */
	synchronized boolean isFresh(long ctr) {
		if (empty || Long.compareUnsigned(ctr, highest) > 0) {
			return true;
		}

		long age = highest - ctr;

		return Long.compareUnsigned(age, size) < 0 && !isSeen(ctr);
	}

	/**
	 * Marks a counter as seen, after its frame authenticated.
	 *
	 * @return False if it was seen meanwhile, or fell out of the window.
	 */
	synchronized boolean accept(long ctr) {
		if (empty) {
			empty = false;
			highest = ctr;
			mark(ctr);
			return true;
		}

		if (Long.compareUnsigned(ctr, highest) > 0) {
			long advance = ctr - highest;

			if (Long.compareUnsigned(advance, size) >= 0) {
				java.util.Arrays.fill(seen, 0);
			}
			else {
				for (long c = highest + 1; c != ctr; c++) {
					clear(c);
				}
			}

			highest = ctr;
			mark(ctr);
			return true;
		}

		if (Long.compareUnsigned(highest - ctr, size) >= 0 || isSeen(ctr)) {
			return false;
		}

		mark(ctr);
		return true;
	}

	private boolean isSeen(long ctr) {
		int bit = index(ctr);

		return (seen[bit >>> 6] & 1L << (bit & 63)) != 0;
	}

	private void mark(long ctr) {
		int bit = index(ctr);

		seen[bit >>> 6] |= 1L << (bit & 63);
	}

	private void clear(long ctr) {
		int bit = index(ctr);

		seen[bit >>> 6] &= ~(1L << (bit & 63));
	}

	private int index(long ctr) {
		return (int) Long.remainderUnsigned(ctr, size);
	}

}
