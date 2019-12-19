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

import java.util.Objects;

/**
 * The RTCSessionDescription class is used by an RTCPeerConnection to expose
 * local and remote session descriptions.
 *
 * @author Alex Andres
 */
public class RTCSessionDescription {

	/**
	 * The type of this RTCSessionDescription.
	 */
	public final RTCSdpType sdpType;

	/**
	 * The string representation of the SDP, if type is "ROLLBACK", this member
	 * is unused.
	 */
	public final String sdp;


	/**
	 * Creates an instance of RTCSessionDescription with the specified SDP and
	 * the SDP type.
	 *
	 * @param sdpType The type of this RTCSessionDescription.
	 * @param sdp     The string representation of the SDP; unused if type is
	 *                {@link RTCSdpType#ROLLBACK}.
	 */
	public RTCSessionDescription(RTCSdpType sdpType, String sdp) {
		this.sdpType = sdpType;
		this.sdp = sdp;
	}

	@Override
	public int hashCode() {
		return Objects.hash(sdp, sdpType);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}

		RTCSessionDescription other = (RTCSessionDescription) obj;

		return Objects.equals(sdp, other.sdp) && sdpType == other.sdpType;
	}

	@Override
	public String toString() {
		return String.format("%s@%d [sdpType=%s, sdp=%s]",
				RTCSessionDescription.class.getSimpleName(), hashCode(),
				sdpType, sdp);
	}
}
