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

import java.util.ArrayList;
import java.util.List;

/**
 * Provides configuration options for {@link RTCRtpTransceiver}s.
 * 
 * @see RTCPeerConnection#addTransceiver
 *
 * @author Alex Andres
 */
public class RTCRtpTransceiverInit {

	/**
	 * The direction of the RTCRtpTransceiver.
	 */
	public RTCRtpTransceiverDirection direction;

	/**
	 * Media stream ids associated with the transceiver.
	 */
	public List<String> streamIds;

	/**
	 * Parameters for sending RTP encodings of media.
	 */
	public List<RTCRtpEncodingParameters> sendEncodings;


	/**
	 * Creates an instance of RTCRtpTransceiverInit.
	 */
	public RTCRtpTransceiverInit() {
		direction = RTCRtpTransceiverDirection.SEND_RECV;
		streamIds = new ArrayList<>();
		sendEncodings = new ArrayList<>();
	}

}
