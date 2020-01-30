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
import dev.onvoid.webrtc.media.MediaStreamTrack;

import java.util.List;

/**
 * The RTCPeerConnection represents a WebRTC connection between the local
 * computer and a remote peer. Communications are coordinated by the exchange of
 * control messages (called a signaling protocol) over a signaling channel.
 *
 * @author Alex Andres
 */
public class RTCPeerConnection extends NativeObject {

	/**
	 * The PeerConnection doesn't take ownership of the observer. When the
	 * PeerConnection is closed the observer will be disposed using this handle.
	 */
	@SuppressWarnings("unused")
	private long observerHandle;


	/**
	 * Constructor used by the native api.
	 */
	private RTCPeerConnection() {

	}

	/**
	 * Returns an array of {@link RTCRtpSender} objects representing the RTP
	 * senders that belong to non-stopped {@link RTCRtpTransceiver} objects
	 * currently attached to this RTCPeerConnection.
	 *
	 * @return An array of RTP senders.
	 */
	public native RTCRtpSender[] getSenders();

	/**
	 * Returns an array of {@link RTCRtpReceiver} objects representing the RTP
	 * receivers that belong to non-stopped {@link RTCRtpTransceiver} objects
	 * currently attached to this RTCPeerConnection.
	 *
	 * @return An array of RTP receivers.
	 */
	public native RTCRtpReceiver[] getReceivers();

	/**
	 * Returns an array of {@link RTCRtpTransceiver} objects representing the
	 * RTP transceivers that are currently attached to this RTCPeerConnection.
	 *
	 * @return An array of RTP transceivers.
	 */
	public native RTCRtpTransceiver[] getTransceivers();

	/**
	 * Adds a new track to the RTCPeerConnection, and indicates that it is
	 * contained in the specified MediaStreams.
	 *
	 * @param track     A MediaStreamTrack representing the media track to
	 *                  transmit.
	 * @param streamIds A list of MediaStream ids to which the track belongs
	 *                  to.
	 *
	 * @return The RTCRtpSender which will be used to transmit the media data.
	 */
	public native RTCRtpSender addTrack(MediaStreamTrack track,
			List<String> streamIds);

	/**
	 * Stops sending media from sender. The RTCRtpSender will still appear in
	 * {@link #getSenders()}. Doing so will cause future calls to {@link
	 * #createOffer} to mark the media description for the corresponding
	 * transceiver as {@link RTCRtpTransceiverDirection#RECV_ONLY} or {@link
	 * RTCRtpTransceiverDirection#INACTIVE}.
	 *
	 * @param sender The RTCRtpSender to remove from the connection.
	 */
	public native void removeTrack(RTCRtpSender sender);

	/**
	 * Create a new RTCRtpTransceiver and add it to the set of transceivers.
	 * Adding a transceiver will cause future calls to {@link #createOffer} to
	 * add a media description for the corresponding transceiver.
	 *
	 * @param track A MediaStreamTrack representing the media track to
	 *              transmit.
	 * @param init  The transceiver configuration options.
	 *
	 * @return The RTCRtpTransceiver which will be used to transmit and receive
	 * the media data.
	 */
	public native RTCRtpTransceiver addTransceiver(MediaStreamTrack track,
			RTCRtpTransceiverInit init);

	/**
	 * Creates a new RTCDataChannel object with the given label. The
	 * RTCDataChannelInit dictionary can be used to configure properties of the
	 * underlying channel such as data reliability.
	 *
	 * @param label A human-readable name for the channel.
	 * @param dict  An RTCDataChannelInit providing configuration options for
	 *              the data channel.
	 *
	 * @return A new RTCDataChannel object.
	 */
	public native RTCDataChannel createDataChannel(String label,
			RTCDataChannelInit dict);

	/**
	 * Initiates the creation of an SDP that contains an RFC 3264 offer with the
	 * supported configurations for the session, including descriptions of the
	 * local MediaStreamTracks attached to this RTCPeerConnection, the
	 * codec/RTP/RTCP capabilities supported by this implementation, and
	 * parameters of the ICE agent and the DTLS connection.
	 *
	 * @param options  The options to provide additional control over the
	 *                 offer.
	 * @param observer The observer to obtain the created RTCSessionDescription.
	 */
	public native void createOffer(RTCOfferOptions options,
			CreateSessionDescriptionObserver observer);

	/**
	 * Generates an SDP answer to an offer received from a remote peer with the
	 * supported configuration for the session that is compatible with the
	 * parameters in the remote configuration. Like {@code createOffer}, the
	 * returned SDP contains descriptions of the local MediaStreamTracks
	 * attached to this RTCPeerConnection, the codec/RTP/RTCP options negotiated
	 * for this session, and any candidates that have been gathered by the ICE
	 * Agent.
	 *
	 * @param options  The options to provide additional control over the
	 *                 answer.
	 * @param observer The observer to obtain the created RTCSessionDescription.
	 */
	public native void createAnswer(RTCAnswerOptions options,
			CreateSessionDescriptionObserver observer);

	/**
	 * Returns the local description that was successfully negotiated the last
	 * time the RTCPeerConnection transitioned into the stable state plus any
	 * local candidates that have been generated by the ICE Agent since the
	 * offer or answer was created.
	 *
	 * @return The last successfully negotiated local description.
	 */
	public native RTCSessionDescription getCurrentLocalDescription();

	/**
	 * Returns a pending local description if it is not null, otherwise it
	 * returns the current local description.
	 *
	 * @return The currently pending local description.
	 */
	public native RTCSessionDescription getLocalDescription();

	/**
	 * Returns a local description that is in the process of being negotiated
	 * plus any local candidates that have been generated by the ICE Agent since
	 * the offer or answer was created. If the RTCPeerConnection is in the
	 * stable state, the value is null.
	 *
	 * @return The local description that is in the process of being negotiated.
	 */
	public native RTCSessionDescription getPendingLocalDescription();

	/**
	 * Returns the last remote description that was successfully negotiated the
	 * last time the RTCPeerConnection transitioned into the stable state plus
	 * any remote candidates that have been supplied via addIceCandidate() since
	 * the offer or answer was created.
	 *
	 * @return The last successfully negotiated remote description.
	 */
	public native RTCSessionDescription getCurrentRemoteDescription();

	/**
	 * Returns a pending remote description if it is not null, otherwise it
	 * returns the current remote description.
	 *
	 * @return The currently pending remote description.
	 */
	public native RTCSessionDescription getRemoteDescription();

	/**
	 * Returns a remote description that is in the process of being negotiated,
	 * complete with any remote candidates that have been supplied via {@link
	 * #addIceCandidate} since the offer or answer was created. If the
	 * RTCPeerConnection is in the stable state, the value is {@code null}.
	 *
	 * @return The remote description that is in the process of being
	 * negotiated.
	 */
	public native RTCSessionDescription getPendingRemoteDescription();

	/**
	 * Instructs the RTCPeerConnection to apply the supplied
	 * RTCSessionDescription as the local description.
	 *
	 * @param description An description which specifies the configuration to be
	 *                    applied to the local end of the connection.
	 * @param observer    The observer to be notified once the description has
	 *                    been successfully set.
	 */
	public native void setLocalDescription(RTCSessionDescription description,
			SetSessionDescriptionObserver observer);

	/**
	 * Instructs the RTCPeerConnection to apply the supplied
	 * RTCSessionDescription as the remote offer or answer. Setting the remote
	 * description changes the local media state.
	 *
	 * @param description An description which specifies the remote peer's
	 *                    current offer or answer.
	 * @param observer    The observer to be notified once the description has
	 *                    been successfully set.
	 */
	public native void setRemoteDescription(RTCSessionDescription description,
			SetSessionDescriptionObserver observer);

	/**
	 * Adds a remote ICE candidate to the ICE Agent.
	 *
	 * @param candidate A new ICE candidate received from the remote peer over a
	 *                  signaling channel.
	 */
	public native void addIceCandidate(RTCIceCandidate candidate);

	/**
	 * Removes a group of remote ICE candidates from the ICE agent.
	 *
	 * @param candidates The ICE candidates to remove.
	 */
	public native void removeIceCandidates(RTCIceCandidate[] candidates);

	/**
	 * Returns the signaling state of the RTCPeerConnection.
	 *
	 * @return The current signaling state.
	 */
	public native RTCSignalingState getSignalingState();

	/**
	 * Returns the ICE gathering state of the RTCPeerConnection.
	 *
	 * @return The current ICE gathering state.
	 */
	public native RTCIceGatheringState getIceGatheringState();

	/**
	 * Returns the ICE connection state of the RTCPeerConnection.
	 *
	 * @return The current ICE connection state.
	 */
	public native RTCIceConnectionState getIceConnectionState();

	/**
	 * Returns the connection state of the RTCPeerConnection.
	 *
	 * @return The current connection state.
	 */
	public native RTCPeerConnectionState getConnectionState();

	/**
	 * Returns an RTCConfiguration object representing the current configuration
	 * of this RTCPeerConnection.
	 *
	 * @return The configuration that indicates the current configuration of
	 * this RTCPeerConnection.
	 */
	public native RTCConfiguration getConfiguration();

	/**
	 * Updates the configuration of this RTCPeerConnection. This includes
	 * changing the configuration of the ICE Agent. When the ICE configuration
	 * changes in a way that requires a new gathering phase, an ICE restart is
	 * required.
	 *
	 * @param configuration The new configuration.
	 */
	public native void setConfiguration(RTCConfiguration configuration);

	/**
	 * Gathers the current statistics of this RTCPeerConnection.
	 *
	 * @param callback The callback to receive the generated stats.
	 */
	public native void getStats(RTCStatsCollectorCallback callback);

	/**
	 * Gathers the current statistics of the specified RTCRtpReceiver.
	 *
	 * @param receiver The RTCRtpReceiver of which to gather the stats.
	 * @param callback The callback to receive the generated stats.
	 */
	public native void getStats(RTCRtpReceiver receiver,
			RTCStatsCollectorCallback callback);

	/**
	 * Gathers the current statistics of the specified RTCRtpSender.
	 *
	 * @param sender   The RTCRtpSender of which to gather the stats.
	 * @param callback The callback to receive the generated stats.
	 */
	public native void getStats(RTCRtpSender sender,
			RTCStatsCollectorCallback callback);

	/**
	 * Tells the RTCPeerConnection that ICE should be restarted. Subsequent
	 * calls to {@code createOffer} will create descriptions that will restart
	 * ICE.
	 */
	public native void restartIce();

	/**
	 * Closes the peer connection, terminates all media and releases any used
	 * resources.
	 */
	public native void close();

}
