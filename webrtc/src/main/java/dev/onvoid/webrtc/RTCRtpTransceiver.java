/*
 * Copyright 2019 Alex Andres
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.onvoid.webrtc;

import dev.onvoid.webrtc.internal.NativeObject;

import java.util.List;

/**
 * Represents a combination of an {@link RTCRtpSender} and an {@link
 * RTCRtpReceiver} that share a common media ID (mid).
 *
 * @author Alex Andres
 */
public class RTCRtpTransceiver extends NativeObject {

	/**
	 * Constructor to be used by the native api.
	 */
	private RTCRtpTransceiver() {

	}

	/**
	 * The mid attribute is the mid negotiated and present in the local and
	 * remote descriptions. Before negotiation is complete, the mid value may be
	 * null. After rollbacks, the value may change from a non-null value to
	 * null.
	 *
	 * @return The media stream identification associated with local and remote
	 * descriptions.
	 */
	public native String getMid();

	/**
	 * The RTCRtpSender corresponding to the RTP media that may be sent with mid
	 * = mid.
	 *
	 * @return The RTP sender associated with this transceiver.
	 */
	public native RTCRtpSender getSender();

	/**
	 * The RTCRtpReceiver corresponding to the RTP media that may be received
	 * with mid = mid.
	 *
	 * @return The RTP receiver associated with this transceiver.
	 */
	public native RTCRtpReceiver getReceiver();

	/**
	 * Indicates the preferred direction of this transceiver, which will be used
	 * in calls to createOffer and createAnswer.
	 *
	 * @return The preferred direction of this transceiver.
	 */
	public native RTCRtpTransceiverDirection getDirection();

	/**
	 * Sets the preferred direction of this transceiver. An update of
	 * directionality does not take effect immediately. Instead, future calls to
	 * createOffer and createAnswer mark the corresponding media description as
	 * SendRecv, SendOnly, RecvOnly or Inactive.
	 *
	 * @param direction The new transceiver direction.
	 */
	public native void setDirection(RTCRtpTransceiverDirection direction);

	/**
	 * Indicates the current direction negotiated for this transceiver. The
	 * value of currentDirection is independent of the value of
	 * RTCRtpEncodingParameters.active since one cannot be deduced from the
	 * other. If this transceiver has never been represented in an offer/answer
	 * exchange, or if the transceiver is stopped, the value is null.
	 *
	 * @return The current direction negotiated for this transceiver.
	 */
	public native RTCRtpTransceiverDirection getCurrentDirection();

	/**
	 * Irreversibly stops the RTCRtpTransceiver. The sender of this transceiver
	 * will no longer send, the receiver will no longer receive. Calling stop()
	 * updates the negotiation-needed flag for the RTCRtpTransceiver's
	 * associated RTCPeerConnection. Stopping a transceiver will cause future
	 * calls to createOffer or createAnswer to generate a zero port in the media
	 * description for the corresponding transceiver.
	 */
	public native void stop();

	/**
	 * Indicates that the sender of this transceiver will no longer send, and
	 * that the receiver will no longer receive. It is true if either stop has
	 * been called or if setting the local or remote description has caused the
	 * RTCRtpTransceiver to be stopped.
	 *
	 * @return true if the transceiver has been stopped, false otherwise.
	 */
	public native boolean stopped();

	/**
	 * Returns the default codec preferences used by WebRTC for this
	 * transceiver.
	 *
	 * @return The default codec preferences.
	 */
	public native List<RTCRtpCodecCapability> getCodecPreferences();

	/**
	 * Overrides the default codec preferences used by WebRTC for this
	 * transceiver. When generating a session description using either {@code
	 * RTCPeerConnection.createOffer} or {@code RTCPeerConnection.createAnswer},
	 * the RTCPeerConnection will use the indicated codecs, in the order
	 * specified in the provided preferences.
	 * <p>
	 * This method allows applications to disable the negotiation of specific
	 * codecs (including RTX/RED/FEC). It also allows an application to cause a
	 * remote peer to prefer the codec that appears first in the list for
	 * sending.
	 * <p>
	 * Setting codecs to an empty sequence resets codec preferences to any
	 * default value.
	 *
	 * @param preferences The preferred codec preferences.
	 */
	public native void setCodecPreferences(
			List<RTCRtpCodecCapability> preferences);

}
