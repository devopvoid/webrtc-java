package dev.onvoid.webrtc.media.audio;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.*;

class AudioDeviceModuleTest {

	private AudioDeviceModule module;


	@BeforeEach
	void initModule() {
		module = new AudioDeviceModule(AudioLayer.kDummyAudio);
	}

	@AfterEach
	void disposeModule() {
		module.dispose();
	}

	@Test
	void setAudioSink() {
		AudioSink sink = new AudioSink() {

			@Override
			public void onRecordedData(byte[] audioSamples, int nSamples,
					int nBytesPerSample, int nChannels, int samplesPerSec,
					int totalDelayMS, int clockDrift) {

			}
		};

		module.setAudioSink(sink);
	}

	@Test
	void setAudioSource() {
		AudioSource source = new AudioSource() {

			@Override
			public int onPlaybackData(byte[] audioSamples, int nSamples,
					int nBytesPerSample, int nChannels, int samplesPerSec) {
				return 0;
			}
		};

		module.setAudioSource(source);
	}

	@Test
	void missingPlayoutDeviceIsRejected() {
		// Not another device in its place, as the first one listed.
		assertThrows(IllegalArgumentException.class,
				() -> module.setPlayoutDevice(missingDevice()));
	}

	@Test
	void missingRecordingDeviceIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> module.setRecordingDevice(missingDevice()));
	}

	/** A device as one unplugged since it was listed looks. */
	private static AudioDevice missingDevice() {
		return new AudioDevice("Unplugged", "no-such-device") { };
	}
}
