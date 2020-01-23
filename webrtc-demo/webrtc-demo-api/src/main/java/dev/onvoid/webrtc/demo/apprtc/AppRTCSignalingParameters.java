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

package dev.onvoid.webrtc.demo.apprtc;

import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCSessionDescription;

import java.util.List;

/**
 * Contains the signaling parameters of an AppRTC room.
 */
public class AppRTCSignalingParameters {

	public final RTCSessionDescription offer;

	public final List<RTCIceCandidate> iceCandidates;

	public final List<RTCIceServer> iceServers;

	public final String clientId;

	public final String wssUrl;

	public final String wssPostUrl;

	public final String iceServerUrl;

	public final boolean initiator;


	public AppRTCSignalingParameters(List<RTCIceServer> iceServers,
									 boolean initiator, String clientId,
									 String wssUrl, String wssPostUrl,
									 String iceServerUrl,
									 RTCSessionDescription offer,
									 List<RTCIceCandidate> iceCandidates) {
		this.iceServers = iceServers;
		this.initiator = initiator;
		this.clientId = clientId;
		this.wssUrl = wssUrl;
		this.wssPostUrl = wssPostUrl;
		this.iceServerUrl = iceServerUrl;
		this.offer = offer;
		this.iceCandidates = iceCandidates;
	}

	@Override
	public String toString() {
		return String.format("%s [iceServers=%s, initiator=%s, clientId=%s, wssUrl=%s, wssPostUrl=%s, offer=%s, iceCandidates=%s]",
							 AppRTCSignalingParameters.class.getSimpleName(), iceServers,
							 initiator, clientId, wssUrl, wssPostUrl, offer,
							 iceCandidates);
	}

}
