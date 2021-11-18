package dev.onvoid.webrtc;

import dev.onvoid.webrtc.media.MediaType;
import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;
import dev.onvoid.webrtc.media.audio.AudioTrack;

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

		List<RTCRtpCodecCapability> codecCapabilities = new ArrayList<>();
		codecCapabilities.add(audioCapabilities.getCodecs().get(0));
		codecCapabilities.add(videoCapabilities.getCodecs().get(0));

		AudioTrackSource audioSource = factory.createAudioSource(new AudioOptions());
		AudioTrack track = factory.createAudioTrack("audioTrack", audioSource);

		RTCPeerConnection peerConnection = connection.getPeerConnection();
		RTCRtpTransceiver transceiver = peerConnection.addTransceiver(track,
				new RTCRtpTransceiverInit());
		transceiver.setCodecPreferences(codecCapabilities);
	}

}
