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
 * The RtcpMuxPolicy affects what ICE candidates are gathered to support
 * non-multiplexed RTCP.
 *
 * @author Alex Andres
 */
public enum RTCRtcpMuxPolicy {

	/**
	 * Gather ICE candidates for both RTP and RTCP candidates. If the
	 * remote-endpoint is capable of multiplexing RTCP, multiplex RTCP on the
	 * RTP candidates. If it is not, use both the RTP and RTCP candidates
	 * separately. Note that, the user agent MAY not implement non-multiplexed
	 * RTCP, in which case it will reject attempts to construct an
	 * RTCPeerConnection with the negotiate policy.
	 */
	NEGOTIATE,

	/**
	 * Gather ICE candidates only for RTP and multiplex RTCP on the RTP
	 * candidates. If the remote endpoint is not capable of rtcp-mux, session
	 * negotiation will fail.
	 */
	REQUIRE;

}
