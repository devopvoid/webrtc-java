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

/**
 * An RTCStatsType indicates the type of the object that the {@link RTCStats}
 * object represents.
 *
 * @author Alex Andres
 */
public enum RTCStatsType {

	/**
	 * Statistics for a codec that is currently being used by RTP streams being
	 * sent or received by the RTCPeerConnection.
	 */
	CODEC,

	/**
	 * Statistics for an inbound RTP stream that is currently received with the
	 * RTCPeerConnection.
	 */
	INBOUND_RTP,

	/**
	 * Statistics for an outbound RTP stream that is currently sent with the
	 * RTCPeerConnection.
	 */
	OUTBOUND_RTP,

	/**
	 * Statistics for the remote endpoint's inbound RTP stream corresponding to
	 * an outbound stream that is currently sent with the RTCPeerConnection. It
	 * is measured at the remote endpoint and reported in an RTCP Receiver
	 * Report (RR) or RTCP Extended Report (XR).
	 */
	REMOTE_INBOUND_RTP,

	/**
	 * Statistics for the remote endpoint's outbound RTP stream corresponding to
	 * an inbound stream that is currently received with the RTCPeerConnection.
	 * It is measured at the remote endpoint and reported in an RTCP Sender
	 * Report (SR).
	 */
	REMOTE_OUTBOUND_RTP,

	/**
	 * Statistics for the media produced by a MediaStreamTrack that is currently
	 * attached to an RTCRtpSender. This reflects the media that is fed to the
	 * encoder; after user-media-constraints have been applied (i.e. not the raw
	 * media produced by the camera). It is either an RTCAudioSourceStats or
	 * RTCVideoSourceStats depending on its kind.
	 */
	MEDIA_SOURCE,

	/**
	 * Statistics for a contributing source (CSRC) that contributed to an
	 * inbound RTP stream.
	 */
	CSRC,

	/**
	 * Statistics related to the RTCPeerConnection.
	 */
	PEER_CONNECTION,

	/**
	 * Statistics related to each RTCDataChannel ID.
	 */
	DATA_CHANNEL,

	/**
	 * Contains statistics related to a specific MediaStream.
	 */
	STREAM,

	/**
	 * Contains statistics related to a specific MediaStreamTrack's attachment
	 * to an RTCRtpSender and the corresponding media-level metrics.
	 */
	TRACK,

	/**
	 * Contains statistics related to a specific RTCRtpSender and the
	 * corresponding media-level metrics.
	 */
	SENDER,

	/**
	 * Contains statistics related to a specific receiver and the corresponding
	 * media-level metrics.
	 */
	RECEIVER,

	/**
	 * Transport statistics related to the RTCPeerConnection.
	 */
	TRANSPORT,

	/**
	 * ICE candidate pair statistics related to the RTCIceTransport.
	 */
	CANDIDATE_PAIR,

	/**
	 * ICE local candidate statistics related to the RTCIceTransport.
	 */
	LOCAL_CANDIDATE,

	/**
	 * ICE remote candidate statistics related to the RTCIceTransport.
	 */
	REMOTE_CANDIDATE,

	/**
	 * Information about a certificate used by an RTCIceTransport.
	 */
	CERTIFICATE,

	/**
	 * Information about the connection to an ICE server (e.g. STUN or TURN).
	 */
	ICE_SERVER;

}
