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
 * Indicates the RTCRtpTransceiver's preferred directionality.
 *
 * @author Alex Andres
 */
public enum RTCRtpTransceiverDirection {

	/**
	 * The RTCRtpTransceiver's RTCRtpSender will offer to send RTP, and will
	 * send RTP if the remote peer accepts and sender.getParameters().encodings[i].active
	 * is true for any value of i.
	 * <p>
	 * The RTCRtpTransceiver's RTCRtpReceiver will offer to receive RTP, and
	 * will receive RTP if the remote peer accepts.
	 */
	SEND_RECV,

	/**
	 * The RTCRtpTransceiver's RTCRtpSender sender will offer to send RTP, and
	 * will send RTP if the remote peer accepts and sender.getParameters().encodings[i].active
	 * is true for any value of i.
	 * <p>
	 * The RTCRtpTransceiver's RTCRtpReceiver will not offer to receive RTP, and
	 * will not receive RTP.
	 */
	SEND_ONLY,

	/**
	 * The RTCRtpTransceiver's RTCRtpSender will not offer to send RTP, and will
	 * not send RTP.
	 * <p>
	 * The RTCRtpTransceiver's RTCRtpReceiver will offer to receive RTP, and
	 * will receive RTP if the remote peer accepts.
	 */
	RECV_ONLY,

	/**
	 * The RTCRtpTransceiver's RTCRtpSender will not offer to send RTP, and will
	 * not send RTP.
	 * <p>
	 * The RTCRtpTransceiver's RTCRtpReceiver will not offer to receive RTP, and
	 * will not receive RTP.
	 */
	INACTIVE,

	/**
	 * The RTCRtpTransceiver will neither send nor receive RTP. It will generate
	 * a zero port in the offer. In answers, its RTCRtpSender will not offer to
	 * send RTP, and its RTCRtpReceiver will not offer to receive RTP. This is a
	 * terminal state.
	 */
	STOPPED;

}
