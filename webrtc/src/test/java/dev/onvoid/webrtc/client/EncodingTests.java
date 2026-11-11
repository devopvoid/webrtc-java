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

import org.junit.jupiter.api.Test;

class EncodingTests {

	@Test
	void rejectsInvalidFrameRates() {
		for (double value : new double[] { Double.NaN, Double.POSITIVE_INFINITY,
				Double.NEGATIVE_INFINITY, 0, -1 }) {
			assertThrows(IllegalArgumentException.class, () -> Encoding.builder().maxFrameRate(value));
		}
	}

	@Test
	void rejectsInvalidScales() {
		for (double value : new double[] { Double.NaN, Double.POSITIVE_INFINITY,
				Double.NEGATIVE_INFINITY, 0, 0.5 }) {
			assertThrows(IllegalArgumentException.class, () -> Encoding.builder().scaleDownBy(value));
		}
	}

	@Test
	void acceptsFiniteBoundaries() {
		Encoding encoding = Encoding.builder().maxFrameRate(Double.MIN_VALUE).scaleDownBy(1).build();
		assertEquals(Double.MIN_VALUE, encoding.maxFrameRate.doubleValue());
		assertEquals(1, encoding.scaleDownBy.doubleValue());
		encoding = Encoding.builder().maxFrameRate(Double.MAX_VALUE).scaleDownBy(Double.MAX_VALUE).build();
		assertEquals(Double.MAX_VALUE, encoding.maxFrameRate.doubleValue());
		assertEquals(Double.MAX_VALUE, encoding.scaleDownBy.doubleValue());
	}
}
