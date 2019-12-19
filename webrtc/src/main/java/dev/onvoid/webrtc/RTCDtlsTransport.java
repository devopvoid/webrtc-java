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

import java.util.List;

import dev.onvoid.webrtc.internal.NativeObject;

/**
 * Allows an application to access information about the Datagram Transport
 * Layer Security (DTLS) transport over which RTP and RTCP packets are sent and
 * received by RTCRtpSender's and RTCRtpReceiver's, as well other data such as
 * SCTP packets sent and received by data channels. RTCDtlsTransport objects are
 * constructed as a result of calls to {@link RTCPeerConnection#setLocalDescription
 * setLocalDescription} and {@link RTCPeerConnection#setRemoteDescription
 * setRemoteDescription}. Each RTCDtlsTransport object represents the DTLS
 * transport layer for the RTP or RTCP component of a specific {@link
 * RTCRtpTransceiver}, or a group of RTCRtpTransceivers if such a group has been
 * negotiated via [BUNDLE].
 *
 * @author Alex Andres
 */
public class RTCDtlsTransport extends NativeObject {

	/**
	 * Returns the underlying transport that is used to send and receive
	 * packets. The underlying transport may not be shared between multiple
	 * active RTCDtlsTransport objects.
	 *
	 * @return The transport that is used to send and receive packets.
	 */
	public native RTCIceTransport getIceTransport();

	/**
	 * Returns the current Datagram Transport Layer Security (DTLS) transport
	 * state.
	 *
	 * @return The current DTLS transport state.
	 */
	public native RTCDtlsTransportState getState();

	/**
	 * Returns the certificate chain in use by the remote side, with each
	 * certificate encoded in PEM format.
	 *
	 * @return The certificate chain in use by the remote side.
	 */
	public native List<RTCCertificatePEM> getRemoteCertificates();

	/**
	 * Register an observer to receive events from this transport. The observer
	 * will replace the previously registered observer.
	 *
	 * @param observer The new DTLS transport observer.
	 */
	public native void registerObserver(RTCDtlsTransportObserver observer);

	/**
	 * Unregister the last set RTCDtlsTransportObserver.
	 */
	public native void unregisterObserver();

}
