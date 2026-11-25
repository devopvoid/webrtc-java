package dev.onvoid.webrtc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.onvoid.webrtc.media.MediaType;
import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.video.VideoDesktopSource;
import dev.onvoid.webrtc.media.video.VideoTrack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RTCRtpTransceiverTests extends TestBase {

	private TestPeerConnection connection;


	@BeforeEach
	void init() {
		connection = new TestPeerConnection(factory);
	}

	@AfterEach
	void dispose() {
		connection.close();
	}

	@Test
	void setCodecCapabilities() {
		RTCRtpCapabilities audioCapabilities = factory
				.getRtpReceiverCapabilities(MediaType.AUDIO);
		RTCRtpCapabilities videoCapabilities = factory
				.getRtpReceiverCapabilities(MediaType.VIDEO);

		List<RTCRtpCodecCapability> audioPreferences = new ArrayList<>();
		audioPreferences.add(audioCapabilities.getCodecs().get(0));

		List<RTCRtpCodecCapability> videoPreferences = new ArrayList<>();
		videoPreferences.add(videoCapabilities.getCodecs().get(0));

		AudioTrackSource audioSource = factory.createAudioSource(new AudioOptions());
		AudioTrack audioTrack = factory.createAudioTrack("audioTrack", audioSource);

		VideoDesktopSource desktopSource = new VideoDesktopSource();
		VideoTrack videoTrack = factory.createVideoTrack("videoTrack", desktopSource);

		RTCPeerConnection peerConnection = connection.getPeerConnection();
		RTCRtpTransceiver audioTransceiver = peerConnection.addTransceiver(audioTrack,
				new RTCRtpTransceiverInit());
		RTCRtpTransceiver videoTransceiver = peerConnection.addTransceiver(videoTrack,
				new RTCRtpTransceiverInit());

		audioTransceiver.setCodecPreferences(audioPreferences);
		videoTransceiver.setCodecPreferences(videoPreferences);
	}

	@Test
	void senderAndReceiverAreIndependentlyDisposable() {
		AudioTrackSource audioSource = factory.createAudioSource(new AudioOptions());
		AudioTrack audioTrack = factory.createAudioTrack("audioTrack", audioSource);

		RTCPeerConnection peerConnection = connection.getPeerConnection();
		RTCRtpTransceiver transceiver = peerConnection.addTransceiver(audioTrack,
				new RTCRtpTransceiverInit());

		// getSender()/getReceiver() used to hand out Java wrappers backed by
		// no owned native reference at all. Querying twice must yield
		// independently disposable instances, and disposing one must not
		// affect the other or the transceiver itself.
		RTCRtpSender firstSender = transceiver.getSender();
		RTCRtpSender secondSender = transceiver.getSender();
		RTCRtpReceiver firstReceiver = transceiver.getReceiver();
		RTCRtpReceiver secondReceiver = transceiver.getReceiver();

		assertNotNull(firstSender);
		assertNotNull(firstReceiver);

		assertDoesNotThrow(firstSender::dispose);
		assertDoesNotThrow(firstReceiver::dispose);

		assertEquals(audioTrack.getId(), secondSender.getTrack().getId());
		assertNotNull(secondReceiver.getParameters());

		assertDoesNotThrow(secondSender::dispose);
		assertDoesNotThrow(secondReceiver::dispose);
		assertDoesNotThrow(transceiver::dispose);
	}

	@Test
	void stopImmediatelyRejectsInAnswer() throws Exception {
		// The answerer declines the audio of an offer of audio and video. With
		// stop(), the answer would keep the audio, as inactive.
		TestPeerConnection offerer = new TestPeerConnection(factory);

		try {
			offerer.getPeerConnection().addTransceiver(MediaType.AUDIO, new RTCRtpTransceiverInit()).dispose();
			offerer.getPeerConnection().addTransceiver(MediaType.VIDEO, new RTCRtpTransceiverInit()).dispose();
			connection.setRemoteDescription(offerer.createOffer());

			for (RTCRtpTransceiver transceiver : connection.getPeerConnection().getTransceivers()) {
				if ("0".equals(transceiver.getMid())) {
					transceiver.stopImmediately();
					assertTrue(transceiver.stopped());
				}

				transceiver.dispose();
			}

			String answer = connection.createAnswer().sdp;

			assertTrue(answer.contains("m=audio 0 "), answer);
			assertFalse(answer.contains("m=video 0 "), answer);
		}
		finally {
			offerer.close();
		}
	}

	@Test
	void throwingParametersStopConversion() {
		RTCRtpCodecCapability codec = factory.getRtpReceiverCapabilities(MediaType.AUDIO)
				.getCodecs().get(0);

		// A codec WebRTC supports, but whose parameters fail to be read. The
		// native code used to go on with the exception pending, making more
		// JNI calls and handing WebRTC a codec without them.
		Map<String, String> parameters = new HashMap<String, String>() {

			@Override
			public Set<Map.Entry<String, String>> entrySet() {
				throw new IllegalStateException("Parameters cannot be read");
			}
		};

		RTCRtpCodecCapability preference = new RTCRtpCodecCapability(codec.getMediaType(),
				codec.getName(), codec.getClockRate(), codec.getChannels(), parameters);

		AudioTrackSource audioSource = factory.createAudioSource(new AudioOptions());
		AudioTrack audioTrack = factory.createAudioTrack("audioTrack", audioSource);

		RTCRtpTransceiver transceiver = connection.getPeerConnection().addTransceiver(audioTrack,
				new RTCRtpTransceiverInit());

		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> transceiver.setCodecPreferences(List.of(preference)));

		assertEquals("Parameters cannot be read", e.getMessage());
		List<RTCRtpCodecCapability> preferences = transceiver.getCodecPreferences();

		// Still none set.
		assertTrue(preferences.isEmpty(), String.valueOf(preferences));
	}

	@Test
	void codecPreferencesReadBack() {
		RTCRtpTransceiver transceiver = audioTransceiver();

		assertTrue(transceiver.getCodecPreferences().isEmpty());

		RTCRtpCodecCapability codec = factory.getRtpReceiverCapabilities(MediaType.AUDIO).getCodecs().get(0);
		transceiver.setCodecPreferences(List.of(codec));

		List<RTCRtpCodecCapability> preferences = transceiver.getCodecPreferences();

		assertEquals(1, preferences.size());
		assertEquals(codec.getName(), preferences.get(0).getName());
		assertEquals(codec.getClockRate(), preferences.get(0).getClockRate());
	}

	@Test
	void stoppedHeaderExtensionNotOffered() throws Exception {
		RTCRtpTransceiver transceiver = audioTransceiver();
		List<RTCRtpHeaderExtensionCapability> extensions = transceiver.getHeaderExtensionsToNegotiate();

		assertTrue(extensions.contains(new RTCRtpHeaderExtensionCapability(AUDIO_LEVEL)), String.valueOf(extensions));

		transceiver.setHeaderExtensionsToNegotiate(withDirection(extensions, AUDIO_LEVEL,
				RTCRtpTransceiverDirection.STOPPED));

		assertTrue(transceiver.getHeaderExtensionsToNegotiate().contains(
				new RTCRtpHeaderExtensionCapability(AUDIO_LEVEL, RTCRtpTransceiverDirection.STOPPED)));
		// Nothing negotiated yet.
		assertTrue(transceiver.getNegotiatedHeaderExtensions().stream()
				.allMatch(e -> e.getDirection() == RTCRtpTransceiverDirection.STOPPED));

		TestPeerConnection callee = new TestPeerConnection(factory);

		try {
			RTCSessionDescription offer = connection.createOffer();

			assertFalse(offer.sdp.contains(AUDIO_LEVEL), offer.sdp);
			assertTrue(offer.sdp.contains(MID), offer.sdp);

			callee.setRemoteDescription(offer);
			connection.setRemoteDescription(callee.createAnswer());

			Map<String, RTCRtpTransceiverDirection> negotiated = transceiver.getNegotiatedHeaderExtensions()
					.stream()
					.collect(Collectors.toMap(RTCRtpHeaderExtensionCapability::getUri,
							RTCRtpHeaderExtensionCapability::getDirection));

			assertEquals(RTCRtpTransceiverDirection.SEND_RECV, negotiated.get(MID), String.valueOf(negotiated));
			assertEquals(RTCRtpTransceiverDirection.STOPPED, negotiated.get(AUDIO_LEVEL), String.valueOf(negotiated));
		}
		finally {
			callee.close();
		}
	}

	@Test
	void invalidHeaderExtensionsRejected() {
		RTCRtpTransceiver transceiver = audioTransceiver();
		List<RTCRtpHeaderExtensionCapability> extensions = transceiver.getHeaderExtensionsToNegotiate();

		List<RTCRtpHeaderExtensionCapability> reordered = new ArrayList<>(extensions);
		java.util.Collections.reverse(reordered);

		assertThrows(IllegalArgumentException.class, () -> transceiver.setHeaderExtensionsToNegotiate(reordered));
		assertThrows(IllegalArgumentException.class, () -> transceiver.setHeaderExtensionsToNegotiate(
				extensions.subList(1, extensions.size())));
		assertThrows(IllegalArgumentException.class, () -> transceiver.setHeaderExtensionsToNegotiate(
				withDirection(extensions, MID, RTCRtpTransceiverDirection.STOPPED)));
		assertThrows(NullPointerException.class, () -> transceiver.setHeaderExtensionsToNegotiate(null));

		// Nothing changed.
		assertEquals(extensions, transceiver.getHeaderExtensionsToNegotiate());
	}

	private static final String AUDIO_LEVEL = "urn:ietf:params:rtp-hdrext:ssrc-audio-level";

	private static final String MID = "urn:ietf:params:rtp-hdrext:sdes:mid";

	private RTCRtpTransceiver audioTransceiver() {
		AudioTrackSource audioSource = factory.createAudioSource(new AudioOptions());
		AudioTrack audioTrack = factory.createAudioTrack("audioTrack", audioSource);

		return connection.getPeerConnection().addTransceiver(audioTrack, new RTCRtpTransceiverInit());
	}

	private static List<RTCRtpHeaderExtensionCapability> withDirection(
			List<RTCRtpHeaderExtensionCapability> extensions, String uri, RTCRtpTransceiverDirection direction) {
		return extensions.stream()
				.map(e -> e.getUri().equals(uri) ? e.withDirection(direction) : e)
				.collect(Collectors.toList());
	}

}
