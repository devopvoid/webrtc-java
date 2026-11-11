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

package dev.onvoid.webrtc.media.player;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.SAME_THREAD)
class ColorSpaceTest {
	@Test
	void preservesColorAndRange() {
		// H.273: 6 = SMPTE 170M (BT.601), 1 = BT.709; range 1 = limited, 2 = full.
		// Red, green, blue, black, middle gray and white. Reference values
		// are rounded from the BT.601/709 Y'CbCr equations for RGB primaries.
		int[][][] references = {
			{ {81,90,240}, {145,54,34}, {41,240,110}, {16,128,128}, {126,128,128}, {235,128,128} },
			{ {76,85,255}, {150,44,21}, {29,255,107}, {0,128,128}, {128,128,128}, {255,128,128} },
			{ {63,102,240}, {173,42,26}, {32,240,118}, {16,128,128}, {126,128,128}, {235,128,128} },
			{ {54,99,255}, {182,30,12}, {18,255,116}, {0,128,128}, {128,128,128}, {255,128,128} }
		};
		int index = 0;
		for (int matrix : new int[] {6, 1}) {
			for (int range : new int[] {1, 2}) {
				for (int[] sample : references[index]) {
					for (int format : new int[] {0, 3}) { // YUV444 conversion and I420 direct path.
						int[] converted = ColorProbe.convert(format, matrix, range, sample[0], sample[1], sample[2]);
						assertNotNull(converted, "conversion failed");
						assertArrayEquals(new int[] {sample[0], sample[1], sample[2], 1, 1, matrix, range}, converted);
						assertArrayEquals(converted, ColorProbe.push(converted, false));
					}
				}
				index++;
			}
		}
	}

	@Test
	void convertsRgbAndInfersJpegRange() {
		int[][] rgb = { {255,0,0}, {0,255,0}, {0,0,255}, {0,0,0}, {128,128,128}, {255,255,255} };
		int[][] yuv = { {76,85,255}, {150,44,21}, {29,255,107}, {0,128,128}, {128,128,128}, {255,128,128} };
		for (int i = 0; i < rgb.length; i++) {
			int[] converted = ColorProbe.convert(1, 0, 0, rgb[i][0], rgb[i][1], rgb[i][2]);
			assertNotNull(converted);
			for (int plane = 0; plane < 3; plane++) {
				assertEquals(yuv[i][plane], converted[plane], 1, "RGB plane " + plane);
			}
			assertArrayEquals(new int[] {1,1,6,2}, java.util.Arrays.copyOfRange(converted, 3, 7));
			assertArrayEquals(converted, ColorProbe.push(converted, false));
		}
		assertArrayEquals(new int[] {0,128,128,1,1,6,2}, ColorProbe.convert(2, 2, 0, 0,128,128));
	}

	@Test
	void defaultsMissingMetadata() {
		assertArrayEquals(new int[] {16,128,128,1,1,6,1}, ColorProbe.convert(0, 2, 0, 16,128,128));
		// Unknown H.273 identifiers must not become invalid WebRTC enum values.
		assertArrayEquals(new int[] {16,128,128,2,2,2,0},
				ColorProbe.push(new int[] {16,128,128,255,255,255,255}, false));
	}

	@Test
	void keepsLegacyPushCompatible() {
		assertArrayEquals(new int[] {16,128,128,-1,-1,-1,-1},
				ColorProbe.push(new int[] {16,128,128,1,1,1,1}, true));
	}
}
