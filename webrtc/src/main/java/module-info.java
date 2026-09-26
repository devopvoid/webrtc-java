module webrtc.java {

	requires java.desktop;

	exports dev.onvoid.webrtc;
	exports dev.onvoid.webrtc.logging;
	exports dev.onvoid.webrtc.media;
	exports dev.onvoid.webrtc.media.audio;
	exports dev.onvoid.webrtc.media.video;
	exports dev.onvoid.webrtc.media.video.desktop;

	// Not API for applications. A native extension module, such as the FFmpeg
	// based media module, needs NativeApi to reach the native side of a custom
	// media source directly instead of carrying every frame through Java.
	exports dev.onvoid.webrtc.internal to webrtc.java.media;

}