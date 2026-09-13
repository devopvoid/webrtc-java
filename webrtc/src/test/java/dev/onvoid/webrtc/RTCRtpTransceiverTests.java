package dev.onvoid.webrtc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.onvoid.webrtc.media.MediaType;
import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.video.VideoDesktopSource;
import dev.onvoid.webrtc.media.video.VideoTrack;

import java.util.ArrayList;
import java.util.List;

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

}
