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
 * The RTCDataChannelInit describes options to configure a {@link
 * RTCDataChannel}.
 *
 * @author Alex Andres
 */
public class RTCDataChannelInit {

	/**
	 * If set to false, data is allowed to be delivered out of order. The
	 * default value of true, guarantees that data will be delivered in order.
	 */
	public boolean ordered = true;

	/**
	 * The default value of false tells the application to announce the channel
	 * in-band and instruct the other peer to dispatch a corresponding
	 * RTCDataChannel. If set to true, it is up to the application to negotiate
	 * the channel and create an RTCDataChannel with the same id at the other
	 * peer.
	 */
	public boolean negotiated = false;

	/**
	 * Limits the time (in milliseconds) during which the channel will transmit
	 * or retransmit data if not acknowledged. This value may be clamped if it
	 * exceeds the maximum value supported by the user agent.
	 */
	public int maxPacketLifeTime = -1;

	/**
	 * Limits the number of times a channel will retransmit data if not
	 * successfully delivered. This value may be clamped if it exceeds the
	 * maximum value supported by the user agent.
	 *
	 * Note: Cannot be set along with maxPacketLifeTime.
	 */
	public int maxRetransmits = -1;

	/**
	 * Overrides the default selection of ID for this channel.
	 */
	public int id = -1;

	/**
	 * Sub-protocol name used for this channel.
	 */
	public String protocol;

	/**
	 * Priority of this channel.
	 */
	public RTCPriorityType priority = RTCPriorityType.LOW;

}
