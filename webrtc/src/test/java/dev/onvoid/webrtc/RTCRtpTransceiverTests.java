package dev.onvoid.webrtc;

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
//		videoTransceiver.setCodecPreferences(videoPreferences);
	}

}
