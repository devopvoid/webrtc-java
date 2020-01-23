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
 * An ICE candidate describes the protocols and routing needed to be able to
 * communicate with a remote device.
 *
 * @author Alex Andres
 */
public class RTCIceCandidate {

	/**
	 * If not null, this contains the media stream "identification-tag" defined
	 * in [RFC5888] for the media component this candidate is associated with.
	 */
	public final String sdpMid;

	/**
	 * If not null, this indicates the index (starting at zero) of the media
	 * description in the SDP this candidate is associated with.
	 */
	public final int sdpMLineIndex;

	/**
	 * SDP string representation of this candidate.
	 */
	public final String sdp;

	/**
	 * The URL of the ICE server which this candidate was gathered from.
	 */
	public final String serverUrl;


	/**
	 * Creates an instance of RTCIceCandidate with the specified parameters.
	 *
	 * @param sdpMid        The media stream "identification-tag"
	 * @param sdpMLineIndex The index (starting at zero) of the media
	 *                      description in the SDP.
	 * @param sdp           SDP string representation of the candidate.
	 */
	public RTCIceCandidate(String sdpMid, int sdpMLineIndex, String sdp) {
		this(sdpMid, sdpMLineIndex, sdp, null);
	}

	/**
	 * Creates an instance of RTCIceCandidate with the specified parameters.
	 *
	 * @param sdpMid        The media stream "identification-tag"
	 * @param sdpMLineIndex The index (starting at zero) of the media
	 *                      description in the SDP.
	 * @param sdp           SDP string representation of the candidate.
	 * @param serverUrl     The URL of the ICE server.
	 */
	public RTCIceCandidate(String sdpMid, int sdpMLineIndex, String sdp,
			String serverUrl) {
		this.sdpMid = sdpMid;
		this.sdpMLineIndex = sdpMLineIndex;
		this.sdp = sdp;
		this.serverUrl = serverUrl;
	}

	@Override
	public String toString() {
		return String.format("%s@%d [sdpMid=%s, sdpMLineIndex=%s, sdp=%s, serverUrl=%s]",
						RTCIceCandidate.class.getSimpleName(), hashCode(),
						sdpMid, sdpMLineIndex, sdp, serverUrl);
	}
}
