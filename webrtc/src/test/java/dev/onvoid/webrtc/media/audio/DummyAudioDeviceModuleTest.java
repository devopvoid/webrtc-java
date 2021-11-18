package dev.onvoid.webrtc.media.audio;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCPeerConnection;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
public class DummyAudioDeviceModuleTest {

	private PeerConnectionFactory factory;


	@BeforeAll
	void initFactory() {
		factory = new PeerConnectionFactory(new AudioDeviceModule(
				AudioLayer.kDummyAudio));
	}

	@AfterAll
	void disposeFactory() {
		factory.dispose();
	}

	@Test
	void createPeerConnectionWithDummyAudio() {
		RTCConfiguration config = new RTCConfiguration();
		RTCPeerConnection peerConnection = factory.createPeerConnection(config,
				candidate -> { });

		assertNotNull(peerConnection);

		peerConnection.close();
	}
}
