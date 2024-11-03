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

package dev.onvoid.webrtc;

import static org.junit.jupiter.api.Assertions.*;

import dev.onvoid.webrtc.media.MediaSource;
import dev.onvoid.webrtc.media.MediaStreamTrackState;
import dev.onvoid.webrtc.media.MediaType;
import dev.onvoid.webrtc.media.audio.*;
import dev.onvoid.webrtc.media.video.VideoDeviceSource;
import dev.onvoid.webrtc.media.video.VideoTrack;

import org.junit.jupiter.api.Test;

class PeerConnectionFactoryTests extends TestBase {

	@Test
	void createWithAudioDeviceModule() {
		AudioDeviceModule audioDevModule = new AudioDeviceModule(AudioLayer.kDummyAudio);

		PeerConnectionFactory factory = new PeerConnectionFactory(audioDevModule);
		factory.dispose();
	}

	@Test
	void createWithAudioProcessing() {
		AudioDeviceModule audioDevModule = new AudioDeviceModule(AudioLayer.kDummyAudio);
		AudioProcessing audioProcessing = new AudioProcessing();

		PeerConnectionFactory factory = new PeerConnectionFactory(audioDevModule, audioProcessing);
		factory.dispose();
	}

	@Test
	void createPeerConnectionNullParams() {
		assertThrows(NullPointerException.class, () -> {
			factory.createPeerConnection(null, candidate -> {
			});
		});

		assertThrows(NullPointerException.class, () -> {
			factory.createPeerConnection(new RTCConfiguration(), null);
		});
	}

	@Test
	void createPeerConnection() {
		RTCConfiguration config = new RTCConfiguration();
		RTCPeerConnection peerConnection = factory.createPeerConnection(config,
				candidate -> { });

		assertNotNull(peerConnection);

		peerConnection.close();
	}

	@Test
	void createAudioSourceNullOptions() {
		assertThrows(NullPointerException.class, () -> {
			factory.createAudioSource(null);
		});
	}

	@Test
	void createAudioSource() {
		AudioOptions audioOptions = new AudioOptions();
		AudioTrackSource audioSource = factory.createAudioSource(audioOptions);

		assertNotNull(audioSource);
		assertEquals(MediaSource.State.LIVE, audioSource.getState());
	}

	@Test
	void createAudioTrackNullParams() {
		assertThrows(NullPointerException.class, () -> {
			factory.createAudioTrack(null, null);
		});

		assertThrows(NullPointerException.class, () -> {
			factory.createAudioTrack("audioTrack", null);
		});
	}

	@Test
	void createAudioTrack() {
		AudioOptions audioOptions = new AudioOptions();
		AudioTrackSource audioSource = factory.createAudioSource(audioOptions);
		AudioTrack audioTrack = factory.createAudioTrack("audioTrack", audioSource);

		assertNotNull(audioTrack);
		assertEquals("audio", audioTrack.getKind());
		assertEquals("audioTrack", audioTrack.getId());
		assertEquals(MediaStreamTrackState.LIVE, audioTrack.getState());
		assertTrue(audioTrack.isEnabled());
	}

	@Test
	void createVideoTrackNullParams() {
		assertThrows(NullPointerException.class, () -> {
			factory.createVideoTrack(null, null);
		});

		assertThrows(NullPointerException.class, () -> {
			factory.createVideoTrack("videoTrack", null);
		});
	}

	@Test
	void createVideoTrack() {
		VideoDeviceSource videoSource = new VideoDeviceSource();
		VideoTrack videoTrack = factory.createVideoTrack("videoTrack", videoSource);

		assertNotNull(videoTrack);
		assertEquals("video", videoTrack.getKind());
		assertEquals("videoTrack", videoTrack.getId());
		assertEquals(MediaStreamTrackState.LIVE, videoTrack.getState());
		assertTrue(videoTrack.isEnabled());
	}

	@Test
	void getReceiverCapabilities() {
		RTCRtpCapabilities audioCapabilities = factory
				.getRtpReceiverCapabilities(MediaType.AUDIO);
		RTCRtpCapabilities videoCapabilities = factory
				.getRtpReceiverCapabilities(MediaType.VIDEO);

		assertNotNull(audioCapabilities);
		assertNotNull(videoCapabilities);

		assertNotNull(audioCapabilities.getCodecs());
		assertNotNull(audioCapabilities.getHeaderExtensions());

		assertFalse(audioCapabilities.getCodecs().isEmpty());
		assertFalse(audioCapabilities.getHeaderExtensions().isEmpty());

		assertNotNull(videoCapabilities.getCodecs());
		assertNotNull(videoCapabilities.getHeaderExtensions());

		assertFalse(videoCapabilities.getCodecs().isEmpty());
		assertFalse(videoCapabilities.getHeaderExtensions().isEmpty());
	}

	@Test
	void getSenderCapabilities() {
		RTCRtpCapabilities audioCapabilities = factory
				.getRtpSenderCapabilities(MediaType.AUDIO);
		RTCRtpCapabilities videoCapabilities = factory
				.getRtpSenderCapabilities(MediaType.VIDEO);

		assertNotNull(audioCapabilities);
		assertNotNull(videoCapabilities);

		assertNotNull(audioCapabilities.getCodecs());
		assertNotNull(audioCapabilities.getHeaderExtensions());

		assertFalse(audioCapabilities.getCodecs().isEmpty());
		assertFalse(audioCapabilities.getHeaderExtensions().isEmpty());

		assertNotNull(videoCapabilities.getCodecs());
		assertNotNull(videoCapabilities.getHeaderExtensions());

		assertFalse(videoCapabilities.getCodecs().isEmpty());
		assertFalse(videoCapabilities.getHeaderExtensions().isEmpty());
	}
}
