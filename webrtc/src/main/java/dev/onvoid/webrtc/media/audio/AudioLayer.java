package dev.onvoid.webrtc.media.audio;

public enum AudioLayer {
	kPlatformDefaultAudio,
	kWindowsCoreAudio,
	kWindowsCoreAudio2,
	kLinuxAlsaAudio,
	kLinuxPulseAudio,
	kAndroidJavaAudio,
	kAndroidOpenSLESAudio,
	kAndroidJavaInputAndOpenSLESOutputAudio,
	kAndroidAAudioAudio,
	kAndroidJavaInputAndAAudioOutputAudio,
	kDummyAudio
}
