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

import dev.onvoid.webrtc.media.MediaStream;

/**
 * RTCPeerConnection callback interface.
 *
 * @author Alex Andres
 */
public interface PeerConnectionObserver {

	/**
	 * The signaling state has changed. This state change is the result of
	 * either setLocalDescription or setRemoteDescription being invoked.
	 *
	 * @param state The new signaling state.
	 */
	default void onSignalingChange(RTCSignalingState state) {
	}

	/**
	 * The RTCPeerConnection connectionState has changed.
	 *
	 * @param state The new connection state.
	 */
	default void onConnectionChange(RTCPeerConnectionState state) {
	}

	/**
	 * The RTCPeerConnection's ICE connection state has changed.
	 *
	 * @param state The new ICE connection state.
	 */
	default void onIceConnectionChange(RTCIceConnectionState state) {
	}

	/**
	 * Called any time the standards-compliant RTCIceConnectionState changes.
	 *
	 * @param state The new ICE connection state.
	 */
	default void onStandardizedIceConnectionChange(RTCIceConnectionState state) {
	}

	/**
	 * The ICE connection is receiving status changes.
	 *
	 * @param receiving True if receiving status changes, false otherwise.
	 */
	default void onIceConnectionReceivingChange(boolean receiving) {
	}

	/**
	 * The RTCPeerConnection's ICE gathering state has changed.
	 *
	 * @param state The new ICE gathering state.
	 */
	default void onIceGatheringChange(RTCIceGatheringState state) {
	}

	/**
	 * A new RTCIceCandidate is made available to the application.
	 *
	 * @param candidate The new ICE candidate.
	 */
	void onIceCandidate(RTCIceCandidate candidate);

	/**
	 * A failure occurred when gathering ICE candidates.
	 *
	 * @param event The ICE gathering error event.
	 */
	default void onIceCandidateError(RTCPeerConnectionIceErrorEvent event) {
	}

	/**
	 * ICE candidates have been removed.
	 *
	 * @param candidates The removed ICE candidates.
	 */
	default void onIceCandidatesRemoved(RTCIceCandidate[] candidates) {
	}

	/**
	 * Media is received on a new stream from the remote peer.
	 *
	 * @param stream The new media stream.
	 */
	default void onAddStream(MediaStream stream) {
	}

	/**
	 * The remote peer has closed a stream.
	 *
	 * @param stream The closed media stream.
	 */
	default void onRemoveStream(MediaStream stream) {
	}

	/**
	 * The remote peer has opened a RTCDataChannel.
	 *
	 * @param dataChannel The opened data channel.
	 */
	default void onDataChannel(RTCDataChannel dataChannel) {
	}

	/**
	 * Triggered to inform the application that session negotiation needs to be
	 * done (i.e. a createOffer call followed by setLocalDescription).
	 */
	default void onRenegotiationNeeded() {
	}

	/**
	 * New incoming media has been negotiated for a specific RTCRtpReceiver, and
	 * that receiver's track has been added to any associated remote
	 * MediaStreams.
	 * <p>
	 * Note: This is called with both Plan B and Unified Plan semantics. Unified
	 * Plan users should prefer OnTrack, OnAddTrack is only called as backwards
	 * compatibility (and is called in the exact same situations as OnTrack).
	 *
	 * @param receiver     The created RTP receiver.
	 * @param mediaStreams Associated remote MediaStreams of the negotiated
	 *                     media track.
	 */
	default void onAddTrack(RTCRtpReceiver receiver, MediaStream[] mediaStreams) {
	}

	/**
	 * Called when signaling indicates that media will no longer be received on
	 * a track.
	 * <p>
	 * With Plan B semantics, the given receiver will have been removed from the
	 * PeerConnection and the track muted.
	 * <p>
	 * With Unified Plan semantics, the receiver will remain but the transceiver
	 * will have changed direction to either "SendOnly" or "Inactive".
	 *
	 * @param receiver The RTP receiver of the removed track.
	 */
	default void onRemoveTrack(RTCRtpReceiver receiver) {
	}

	/**
	 * Called when signaling indicates a transceiver will be receiving media
	 * from the remote peer, as a result of SetRemoteDescription.
	 * <p>
	 * Note: This will only be called if Unified Plan semantics are specified.
	 *
	 * @param transceiver The RTP transceiver of the created track.
	 */
	default void onTrack(RTCRtpTransceiver transceiver) {
	}

}
