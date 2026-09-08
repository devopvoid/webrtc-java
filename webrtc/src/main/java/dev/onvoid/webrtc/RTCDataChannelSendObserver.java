package dev.onvoid.webrtc;

/**
 * Receives the result of one {@link RTCDataChannel#sendAsync(RTCDataChannelBuffer,
 * RTCDataChannelSendObserver)} operation. Callbacks must not block or call WebRTC
 * synchronously; dispatch further work to an application executor.
 */
public interface RTCDataChannelSendObserver {

	/**
	 * The local send operation accepted the message. This does not confirm
	 * delivery to the peer.
	 */
	void onSuccess();

	/**
	 * The send failed or was discarded before completion.
	 *
	 * @param error The error type in brackets followed by the error message.
	 */
	void onFailure(String error);
}
