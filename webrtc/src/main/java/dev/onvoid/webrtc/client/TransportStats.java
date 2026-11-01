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

package dev.onvoid.webrtc.client;

import java.time.Duration;

/**
 * The statistics of one transport of a peer, one ICE and DTLS connection. A
 * peer that bundles its media, as it does by default, has one. One that does
 * not, because the remote side refused to bundle, has one for each m-line.
 */
public final class TransportStats {

	private final String id;

	private final String selectedCandidatePairId;

	private final String candidateType;

	private final Duration roundTripTime;

	private final long bytesSent;

	private final long bytesReceived;


	TransportStats(String id, String selectedCandidatePairId, String candidateType,
			Duration roundTripTime, long bytesSent, long bytesReceived) {
		this.id = id;
		this.selectedCandidatePairId = selectedCandidatePairId;
		this.candidateType = candidateType;
		this.roundTripTime = roundTripTime;
		this.bytesSent = bytesSent;
		this.bytesReceived = bytesReceived;
	}

	/**
	 * @return the id of the transport's entry in the report.
	 */
	public String id() {
		return id;
	}

	/**
	 * @return the id of the candidate pair in use, its entry in the report, or
	 * null while there is none.
	 */
	public String selectedCandidatePairId() {
		return selectedCandidatePairId;
	}

	/**
	 * @return the type of the local candidate in use: {@code host}, {@code
	 * srflx}, {@code prflx} or {@code relay}. Null while not connected.
	 */
	public String candidateType() {
		return candidateType;
	}

	/**
	 * @return the current round trip time of the candidate pair in use, or
	 * null while not measured.
	 */
	public Duration roundTripTime() {
		return roundTripTime;
	}

	/**
	 * @return the bytes sent on this transport so far.
	 */
	public long bytesSent() {
		return bytesSent;
	}

	/**
	 * @return the bytes received on this transport so far.
	 */
	public long bytesReceived() {
		return bytesReceived;
	}

	@Override
	public String toString() {
		return "TransportStats[" + id + ", candidate=" + candidateType + ", rtt=" + roundTripTime
				+ ", sent=" + bytesSent + " B, received=" + bytesReceived + " B]";
	}
}
