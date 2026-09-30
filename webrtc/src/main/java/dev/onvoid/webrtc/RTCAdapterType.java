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
 * A kind of network adapter, as {@link RTCConfiguration#networkPreference}
 * takes it.
 *
 * @author Alex Andres
 */
public enum RTCAdapterType {

	/**
	 * An adapter of unknown kind.
	 */
	UNKNOWN,

	/**
	 * A wired Ethernet adapter.
	 */
	ETHERNET,

	/**
	 * A Wi-Fi adapter.
	 */
	WIFI,

	/**
	 * A cellular adapter.
	 */
	CELLULAR,

	/**
	 * A VPN.
	 */
	VPN,

	/**
	 * The loopback adapter.
	 */
	LOOPBACK,

	/**
	 * An adapter bound to any address.
	 */
	ANY

}
