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

import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.Test;

class PeerConnectionFactoryTests extends TestBase {

	@Test
	void createWithoutAudioDeviceModule() {
		// Without a module the factory creates the platform default. On a host
		// without an audio system that must fail with an exception, not abort
		// the process.
		try {
			PeerConnectionFactory factory = new PeerConnectionFactory();
			factory.dispose();
		}
		catch (Error e) {
			assertTrue(e.getMessage().contains("AudioDeviceModule"), e.getMessage());
		}
	}

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
	void createWithFieldTrials() {
		AudioDeviceModule audioDevModule = new AudioDeviceModule(AudioLayer.kDummyAudio);
		Map<String, String> fieldTrials = Collections.singletonMap("WebRTC-Bar", "Enabled");

		PeerConnectionFactory factory = new PeerConnectionFactory(fieldTrials, audioDevModule);
		factory.dispose();
	}

	@Test
	void createWithEmptyFieldTrials() {
		AudioDeviceModule audioDevModule = new AudioDeviceModule(AudioLayer.kDummyAudio);

		PeerConnectionFactory factory = new PeerConnectionFactory(Collections.emptyMap(), audioDevModule);
		factory.dispose();
	}

	@Test
	void createWithInvalidFieldTrials() {
		AudioDeviceModule audioDevModule = new AudioDeviceModule(AudioLayer.kDummyAudio);
		Map<String, String> fieldTrials = Collections.singletonMap("WebRTC-Bar", "");

		assertThrows(Error.class, () -> {
			new PeerConnectionFactory(fieldTrials, audioDevModule);
		});
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
	void customAudioSourceAfterDeviceAudioSourceIsRejected() {
		// WebRTC feeds device-captured audio into every audio sender of a
		// factory. A CustomAudioSource track would be fed twice, which aborts
		// the process inside WebRTC, so the factory must refuse it up front.
		AudioDeviceModule audioModule = new AudioDeviceModule(AudioLayer.kDummyAudio);
		PeerConnectionFactory audioFactory = new PeerConnectionFactory(audioModule);
		CustomAudioSource customSource = new CustomAudioSource();

		try {
			AudioTrackSource deviceSource = audioFactory.createAudioSource(new AudioOptions());

			IllegalStateException e = assertThrows(IllegalStateException.class, () -> {
				audioFactory.createAudioTrack("customTrack", customSource);
			});
			assertTrue(e.getMessage().contains("CustomAudioSource"), e.getMessage());

			// The device path stays usable.
			AudioTrack deviceTrack = audioFactory.createAudioTrack("deviceTrack", deviceSource);
			assertNotNull(deviceTrack);

			deviceTrack.dispose();
			deviceSource.dispose();
		}
		finally {
			customSource.dispose();
			audioFactory.dispose();
			audioModule.dispose();
		}
	}

	@Test
	void deviceAudioSourceAfterCustomAudioSourceIsRejected() {
		AudioDeviceModule audioModule = new AudioDeviceModule(AudioLayer.kDummyAudio);
		PeerConnectionFactory audioFactory = new PeerConnectionFactory(audioModule);
		CustomAudioSource customSource = new CustomAudioSource();
		CustomAudioSource otherCustomSource = new CustomAudioSource();

		try {
			AudioTrack customTrack = audioFactory.createAudioTrack("customTrack", customSource);
			assertNotNull(customTrack);

			IllegalStateException e = assertThrows(IllegalStateException.class, () -> {
				audioFactory.createAudioSource(new AudioOptions());
			});
			assertTrue(e.getMessage().contains("AudioDeviceModule"), e.getMessage());

			// Any number of custom sources may share the factory.
			AudioTrack otherCustomTrack = audioFactory.createAudioTrack("otherCustomTrack", otherCustomSource);
			assertNotNull(otherCustomTrack);

			otherCustomTrack.dispose();
			customTrack.dispose();
		}
		finally {
			otherCustomSource.dispose();
			customSource.dispose();
			audioFactory.dispose();
			audioModule.dispose();
		}
	}

	@Test
	void sinkFedTrackAddedToDeviceAudioFactoryIsRejected() {
		// A track whose audio is pushed rather than captured, here one backed by
		// a CustomAudioSource but equally one forwarded from a remote peer, may
		// not become a sender of a factory that sends device-captured audio.
		AudioDeviceModule deviceModule = new AudioDeviceModule(AudioLayer.kDummyAudio);
		AudioDeviceModule customModule = new AudioDeviceModule(AudioLayer.kDummyAudio);
		PeerConnectionFactory deviceFactory = new PeerConnectionFactory(deviceModule);
		PeerConnectionFactory customFactory = new PeerConnectionFactory(customModule);
		CustomAudioSource customSource = new CustomAudioSource();

		try {
			AudioTrackSource deviceSource = deviceFactory.createAudioSource(new AudioOptions());
			AudioTrack deviceTrack = deviceFactory.createAudioTrack("deviceTrack", deviceSource);
			AudioTrack customTrack = customFactory.createAudioTrack("customTrack", customSource);

			RTCPeerConnection peerConnection = deviceFactory.createPeerConnection(
					new RTCConfiguration(), candidate -> { });

			// The device-captured track is what this factory sends.
			RTCRtpSender sender = peerConnection.addTrack(deviceTrack,
					Collections.singletonList("stream0"));

			assertNotNull(sender);

			IllegalStateException e = assertThrows(IllegalStateException.class, () -> {
				peerConnection.addTrack(customTrack, Collections.singletonList("stream1"));
			});
			assertTrue(e.getMessage().contains("AudioDeviceModule"), e.getMessage());

			// A transceiver that sends is rejected for the same reason.
			RTCRtpTransceiverInit sendRecv = new RTCRtpTransceiverInit();

			assertThrows(IllegalStateException.class, () -> {
				peerConnection.addTransceiver(customTrack, sendRecv);
			});

			// A receive-only transceiver never sends the track, so it is allowed.
			RTCRtpTransceiverInit recvOnly = new RTCRtpTransceiverInit();
			recvOnly.direction = RTCRtpTransceiverDirection.RECV_ONLY;

			RTCRtpTransceiver transceiver = peerConnection.addTransceiver(customTrack, recvOnly);

			assertNotNull(transceiver);

			transceiver.dispose();
			sender.dispose();
			peerConnection.close();
			deviceTrack.dispose();
			customTrack.dispose();
			deviceSource.dispose();
		}
		finally {
			customSource.dispose();
			customFactory.dispose();
			deviceFactory.dispose();
			customModule.dispose();
			deviceModule.dispose();
		}
	}

	@Test
	void sinkFedTrackAddedFirstCommitsFactoryToPushedAudio() {
		// Adding a track whose audio is pushed settles the question for the
		// factory that owns the connection, even though that factory did not
		// create the track. A forwarded remote track reaches a factory this way.
		AudioDeviceModule receivingModule = new AudioDeviceModule(AudioLayer.kDummyAudio);
		AudioDeviceModule sendingModule = new AudioDeviceModule(AudioLayer.kDummyAudio);
		PeerConnectionFactory receivingFactory = new PeerConnectionFactory(receivingModule);
		PeerConnectionFactory sendingFactory = new PeerConnectionFactory(sendingModule);
		CustomAudioSource customSource = new CustomAudioSource();

		try {
			AudioTrack customTrack = sendingFactory.createAudioTrack("customTrack", customSource);

			RTCPeerConnection peerConnection = receivingFactory.createPeerConnection(
					new RTCConfiguration(), candidate -> { });

			RTCRtpSender sender = peerConnection.addTrack(customTrack,
					Collections.singletonList("stream0"));

			assertNotNull(sender);

			// The receiving factory now sends pushed audio, so its own device
			// capture is off limits.
			assertThrows(IllegalStateException.class, () -> {
				receivingFactory.createAudioSource(new AudioOptions());
			});

			sender.dispose();
			peerConnection.close();
			customTrack.dispose();
		}
		finally {
			customSource.dispose();
			sendingFactory.dispose();
			receivingFactory.dispose();
			sendingModule.dispose();
			receivingModule.dispose();
		}
	}

	@Test
	void customAudioTrackNullParamsDoNotCommitFactory() {
		AudioDeviceModule audioModule = new AudioDeviceModule(AudioLayer.kDummyAudio);
		PeerConnectionFactory audioFactory = new PeerConnectionFactory(audioModule);
		CustomAudioSource customSource = new CustomAudioSource();

		try {
			assertThrows(NullPointerException.class, () -> {
				audioFactory.createAudioTrack(null, customSource);
			});
			assertThrows(NullPointerException.class, () -> {
				audioFactory.createAudioSource(null);
			});

			// The rejected custom-track call must not have committed the factory
			// to pushed audio, so a device-captured source is still allowed.
			AudioTrackSource deviceSource = audioFactory.createAudioSource(new AudioOptions());
			assertNotNull(deviceSource);

			deviceSource.dispose();
		}
		finally {
			customSource.dispose();
			audioFactory.dispose();
			audioModule.dispose();
		}
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
