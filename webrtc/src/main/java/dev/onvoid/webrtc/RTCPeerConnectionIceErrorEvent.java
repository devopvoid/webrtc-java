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
 * This event occurs when the {@link RTCPeerConnection} fails to gather an ICE
 * candidate.
 *
 * @author Alex Andres
 */
public class RTCPeerConnectionIceErrorEvent {

	private final String address;

	private final Integer port;

	private final String url;

	private final int errorCode;

	private final String errorText;


	RTCPeerConnectionIceErrorEvent(String address, Integer port, String url,
								   int errorCode, String errorText) {
		this.address = address;
		this.port = port;
		this.url = url;
		this.errorCode = errorCode;
		this.errorText = errorText;
	}

	/**
	 * Returns the local IP address used to communicate with the STUN or TURN
	 * server.
	 * <p>
	 * On a multi-homed system, multiple interfaces may be used to contact the
	 * server, and this attribute allows the application to figure out on which
	 * one the failure occurred.
	 * <p>
	 * If the local IP address value is not already exposed as part of a local
	 * candidate, the address attribute will be set to {@code null}.
	 *
	 * @return The local IP address or {@code null}, if the IP address is not
	 * already exposed.
	 */
	public String getAddress() {
		return address;
	}

	/**
	 * Returns the port used to communicate with the STUN or TURN server.
	 * <p>
	 * If the address is {@code null}, the port is also set to {@code null}.
	 *
	 * @return The port or {@code null}, if the address is {@code null}.
	 */
	public Integer getPort() {
		return port;
	}

	/**
	 * The STUN or TURN URL that identifies the STUN or TURN server for which
	 * the failure occurred.
	 *
	 * @return The STUN or TURN URL.
	 */
	public String getUrl() {
		return url;
	}

	/**
	 * The numeric STUN error code returned by the STUN or TURN server.
	 * <p>
	 * If no host candidate can reach the server, errorCode will be set to the
	 * value 701 which is outside the STUN error code range. This error is only
	 * fired once per server URL while in the {@link RTCIceGatheringState} of
	 * "GATHERING".
	 *
	 * @return The numeric STUN error code
	 */
	public int getErrorCode() {
		return errorCode;
	}

	/**
	 * The STUN reason text returned by the STUN or TURN server.
	 * <p>
	 * If the server could not be reached, errorText will be set to an
	 * implementation-specific value providing details about the error.
	 *
	 * @return The STUN reason text returned by the STUN or TURN server.
	 */
	public String getErrorText() {
		return errorText;
	}
}
