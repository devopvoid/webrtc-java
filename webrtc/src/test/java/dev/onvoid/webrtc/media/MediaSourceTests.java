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

package dev.onvoid.webrtc.media;

import static org.junit.jupiter.api.Assertions.*;

import dev.onvoid.webrtc.TestBase;
import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;

import org.junit.jupiter.api.Test;

class MediaSourceTests extends TestBase {

	@Test
	void audioSourceStateAfterCreation() {
		AudioOptions audioOptions = new AudioOptions();
		AudioTrackSource audioSource = factory.createAudioSource(audioOptions);

		assertEquals(MediaSource.State.LIVE, audioSource.getState());

		audioSource.dispose();
	}

	@Test
	void audioSourceDisposeAfterTrackDispose() {
		// Regression test: AudioTrackSource used to have no dispose(), so the
		// native AudioSourceInterface reference obtained from
		// createAudioSource() could never be released by the application.
		AudioOptions audioOptions = new AudioOptions();
		AudioTrackSource audioSource = factory.createAudioSource(audioOptions);
		AudioTrack audioTrack = factory.createAudioTrack("audio0", audioSource);

		audioTrack.dispose();
		audioSource.dispose();
	}

}
