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

import java.util.List;

import dev.onvoid.webrtc.media.video.VideoCaptureCapability;

import org.junit.jupiter.api.Test;

class NearestCapabilityTests {

	private static final VideoCaptureCapability SMALL = new VideoCaptureCapability(640, 480, 30);

	private static final VideoCaptureCapability LARGE = new VideoCaptureCapability(1920, 1080, 30);


	@Test
	void picksNearestResolution() {
		assertEquals(SMALL, nearest(800, 600, SMALL, LARGE));
		assertEquals(LARGE, nearest(1600, 900, SMALL, LARGE));
	}

	/*
	 * The width alone is close to overflowing an int, the distance of the small
	 * format, width and height together, is past it.
	 */
	@Test
	void hugeResolutionPicksNearest() {
		assertEquals(LARGE, nearest(Integer.MAX_VALUE, 2160, SMALL, LARGE));
		assertEquals(LARGE, nearest(Integer.MAX_VALUE, 2160, LARGE, SMALL));
	}

	private static VideoCaptureCapability nearest(int width, int height, VideoCaptureCapability... capabilities) {
		CameraOptions options = CameraOptions.builder().resolution(width, height).build();

		return LocalMediaImpl.nearest(List.of(capabilities), options);
	}
}
