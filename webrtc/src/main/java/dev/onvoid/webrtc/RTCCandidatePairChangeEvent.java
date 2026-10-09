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
 * This event occurs when ICE selects, or later re-selects, the candidate pair
 * that carries a transport's traffic.
 *
 * @author Alex Andres
 *
 * @see PeerConnectionObserver#onSelectedCandidatePairChanged(RTCCandidatePairChangeEvent)
 */
public class RTCCandidatePairChangeEvent {

	private final RTCIceCandidate local;

	private final RTCIceCandidate remote;

	private final long lastDataReceivedMs;

	private final String reason;

	private final long estimatedDisconnectedTimeMs;

	/*
	 * The remote candidate's address, port and type, as the older
	 * PeerConnectionObserver callback reports them.
	 */
	final String remoteAddress;

	final int remotePort;

	final String remoteType;


	RTCCandidatePairChangeEvent(RTCIceCandidate local, RTCIceCandidate remote,
			long lastDataReceivedMs, String reason,
			long estimatedDisconnectedTimeMs, String remoteAddress,
			int remotePort, String remoteType) {
		this.local = local;
		this.remote = remote;
		this.lastDataReceivedMs = lastDataReceivedMs;
		this.reason = reason;
		this.estimatedDisconnectedTimeMs = estimatedDisconnectedTimeMs;
		this.remoteAddress = remoteAddress;
		this.remotePort = remotePort;
		this.remoteType = remoteType;
	}

	/**
	 * Returns the local candidate of the selected pair. Its {@code sdpMid} is
	 * the MID of the transport the pair belongs to, its {@code sdpMLineIndex}
	 * is -1 and its {@code sdp} is the candidate attribute.
	 *
	 * @return The local candidate of the selected pair.
	 */
	public RTCIceCandidate getLocal() {
		return local;
	}

	/**
	 * Returns the remote candidate of the selected pair, in the same form as
	 * {@link #getLocal()}. For a relayed connection its address is the TURN
	 * relay's address.
	 *
	 * @return The remote candidate of the selected pair.
	 */
	public RTCIceCandidate getRemote() {
		return remote;
	}

	/**
	 * Returns when the transport last received data, in milliseconds on
	 * WebRTC's monotonic clock. It is only meaningful when compared with
	 * another value from the same clock, such as the one of a later event.
	 *
	 * @return The time data was last received, in milliseconds.
	 */
	public long getLastDataReceivedMs() {
		return lastDataReceivedMs;
	}

	/**
	 * Returns why ICE switched to this pair, as a human-readable text that
	 * WebRTC does not define further, for example "remote candidate
	 * generation maybe changed".
	 *
	 * @return The reason for the change.
	 */
	public String getReason() {
		return reason;
	}

	/**
	 * Returns how long WebRTC estimates the transport was disconnected before
	 * this pair was selected, in milliseconds: the time since the previous
	 * pair, or the transport, last received anything. It is 0 for the first
	 * pair selected on a transport.
	 *
	 * @return The estimated time without a working pair, in milliseconds.
	 */
	public long getEstimatedDisconnectedTimeMs() {
		return estimatedDisconnectedTimeMs;
	}

	@Override
	public String toString() {
		return String.format("%s@%d [local=%s, remote=%s, lastDataReceivedMs=%d, reason=%s, estimatedDisconnectedTimeMs=%d]",
				RTCCandidatePairChangeEvent.class.getSimpleName(), hashCode(),
				local, remote, lastDataReceivedMs, reason,
				estimatedDisconnectedTimeMs);
	}
}
