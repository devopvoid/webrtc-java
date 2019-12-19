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
 * The RTCConfiguration defines a set of parameters to configure how the
 * peer-to-peer communication established via {@link RTCPeerConnection} is
 * established or re-established.
 *
 * @author Alex Andres
 */
public class RTCConfiguration {

	/**
	 * A list of ICE server's describing servers available to be used by ICE,
	 * such as STUN and TURN servers.
	 */
	public List<RTCIceServer> iceServers;

	/**
	 * Indicates which candidates the ICE Agent is allowed to use.
	 */
	public RTCIceTransportPolicy iceTransportPolicy;

	/**
	 * Indicates which media-bundling policy to use when gathering ICE
	 * candidates.
	 */
	public RTCBundlePolicy bundlePolicy;

	/**
	 * Indicates which rtcp-mux policy to use when gathering ICE candidates.
	 */
	public RTCRtcpMuxPolicy rtcpMuxPolicy;

	/**
	 * A list of certificates that the RTCPeerConnection uses to authenticate.
	 * <p>
	 * If this value is absent, then a default set of certificates is generated
	 * for each RTCPeerConnection instance.
	 */
	public List<RTCCertificatePEM> certificates;


	/**
	 * Creates an instance of RTCConfiguration.
	 */
	public RTCConfiguration() {
		iceServers = new ArrayList<>();
		iceTransportPolicy = RTCIceTransportPolicy.ALL;
		bundlePolicy = RTCBundlePolicy.BALANCED;
		rtcpMuxPolicy = RTCRtcpMuxPolicy.REQUIRE;
		certificates = new ArrayList<>();
	}

}
