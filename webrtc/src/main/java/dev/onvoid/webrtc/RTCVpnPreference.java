/*
 * Copyright 2026 Alex Andres
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
 * How ICE treats VPN connections, as {@link RTCConfiguration#vpnPreference}
 * takes it.
 *
 * @author Alex Andres
 */
public enum RTCVpnPreference {

	/**
	 * No preference.
	 */
	DEFAULT,

	/**
	 * Use only VPN connections.
	 */
	ONLY_USE_VPN,

	/**
	 * Never use VPN connections.
	 */
	NEVER_USE_VPN,

	/**
	 * Use a VPN connection where there is one.
	 */
	PREFER_VPN,

	/**
	 * Use a VPN connection only where there is no other.
	 */
	AVOID_VPN

}
