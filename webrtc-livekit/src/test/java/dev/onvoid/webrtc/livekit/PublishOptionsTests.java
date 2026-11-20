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

package dev.onvoid.webrtc.livekit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class PublishOptionsTests {

	@Test
	void threeLayersFrom720() {
		List<PublishOptions.Layer> layers = layers(1280, 720);

		assertEquals("LOW 320x180 160000, MEDIUM 640x360 450000, HIGH 1280x720 1700000", describe(layers));
		assertEquals(List.of("q", "h", "f"), options(1280, 720).simulcastLayers().rids());
	}

	@Test
	void twoLayersFrom360() {
		assertEquals("LOW 320x180 160000, MEDIUM 640x360 450000", describe(layers(640, 360)));
		assertEquals("LOW 427x270 160000, MEDIUM 853x540 800000", describe(layers(853, 540)));
	}

	@Test
	void noLayersBelow360() {
		assertTrue(layers(480, 270).isEmpty());
		assertNull(options(480, 270).simulcastLayers());
	}

	@Test
	void noLayersWithoutSimulcast() {
		PublishOptions options = PublishOptions.builder().videoSize(1920, 1080).build();

		assertTrue(options.layers().isEmpty());
	}

	@Test
	void simulcastNeedsSize() {
		assertThrows(IllegalStateException.class, () -> PublishOptions.builder().simulcast(true).build());
		assertThrows(IllegalArgumentException.class, () -> PublishOptions.builder().videoSize(0, 720));
	}

	private static PublishOptions options(int width, int height) {
		return PublishOptions.builder().videoSize(width, height).simulcast(true).build();
	}

	private static List<PublishOptions.Layer> layers(int width, int height) {
		return options(width, height).layers();
	}

	private static String describe(List<PublishOptions.Layer> layers) {
		return layers.stream()
				.map(layer -> layer.quality + " " + layer.width + "x" + layer.height + " " + layer.bitrate)
				.collect(Collectors.joining(", "));
	}

}
