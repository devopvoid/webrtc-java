package dev.onvoid.webrtc.media.audio;

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
}
