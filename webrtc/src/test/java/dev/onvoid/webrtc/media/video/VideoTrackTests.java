/*
 * Copyright 2019 Alex Andres
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

package dev.onvoid.webrtc.media.video;

import static org.junit.jupiter.api.Assertions.*;

import dev.onvoid.webrtc.TestBase;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VideoTrackTests extends TestBase {

	private VideoTrack videoTrack;


	@BeforeEach
	void init() {
		VideoDeviceSource videoSource = new VideoDeviceSource();

		videoTrack = factory.createVideoTrack("videoTrack", videoSource);
	}

	@AfterEach
	void dispose() {
		videoTrack.dispose();
	}

	@Test
	void disableEnableTrack() {
		videoTrack.setEnabled(false);

		assertFalse(videoTrack.isEnabled());

		videoTrack.setEnabled(true);

		assertTrue(videoTrack.isEnabled());
	}

	@Test
	void addNullSink() {
		assertThrows(NullPointerException.class, () -> videoTrack.addSink(null));
	}

	@Test
	void removeNullSink() {
		assertThrows(NullPointerException.class, () -> videoTrack.removeSink(null));
	}

	@Test
	void addRemoveSink() {
		VideoTrackSink sink = frame -> { };

		videoTrack.addSink(sink);
		videoTrack.removeSink(sink);
	}

}
