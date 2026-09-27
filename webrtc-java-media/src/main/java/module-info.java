/**
 * Media extension for webrtc-java: reads media files and network streams with
 * FFmpeg and feeds them into a peer connection, and records what a peer
 * connection sends or receives into media files.
 */
module webrtc.java.media {

	requires webrtc.java;

	exports dev.onvoid.webrtc.media.player;
	exports dev.onvoid.webrtc.media.recorder;

}
