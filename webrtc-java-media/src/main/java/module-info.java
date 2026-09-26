/**
 * Media extension for webrtc-java: reads media files and network streams with
 * FFmpeg and feeds them into a peer connection.
 */
module webrtc.java.media {

	requires webrtc.java;

	exports dev.onvoid.webrtc.media.player;

}
