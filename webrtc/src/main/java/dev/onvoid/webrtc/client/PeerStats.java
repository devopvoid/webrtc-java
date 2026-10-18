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
import java.time.Instant;
import java.util.List;

import dev.onvoid.webrtc.RTCStatsReport;

/**
 * The statistics people look for first, taken from an {@link RTCStatsReport}.
 * Bitrates are computed between two samples, so the first sample a peer takes
 * has none.
 */
public final class PeerStats {

	private final Instant timestamp;

	private final String candidateType;

	private final Duration roundTripTime;

	private final long bytesSent;

	private final long bytesReceived;

	private final long outgoingBitrate;

	private final long incomingBitrate;

	private final double packetLoss;

	private final List<VideoStats> outboundVideo;

	private final List<VideoStats> inboundVideo;

	private final RTCStatsReport report;


	PeerStats(Instant timestamp, String candidateType, Duration roundTripTime,
			long bytesSent, long bytesReceived, long outgoingBitrate,
			long incomingBitrate, double packetLoss,
			List<VideoStats> outboundVideo, List<VideoStats> inboundVideo,
			RTCStatsReport report) {
		this.timestamp = timestamp;
		this.candidateType = candidateType;
		this.roundTripTime = roundTripTime;
		this.bytesSent = bytesSent;
		this.bytesReceived = bytesReceived;
		this.outgoingBitrate = outgoingBitrate;
		this.incomingBitrate = incomingBitrate;
		this.packetLoss = packetLoss;
		this.outboundVideo = outboundVideo;
		this.inboundVideo = inboundVideo;
		this.report = report;
	}

	/**
	 * @return when the statistics were taken.
	 */
	public Instant timestamp() {
		return timestamp;
	}

	/**
	 * @return the type of the local candidate in use: {@code host}, {@code
	 * srflx}, {@code prflx} or {@code relay}. Null while not connected.
	 */
	public String candidateType() {
		return candidateType;
	}

	/**
	 * @return the current round trip time, or null while not measured.
	 */
	public Duration roundTripTime() {
		return roundTripTime;
	}

	/**
	 * @return the bytes sent on the connection so far.
	 */
	public long bytesSent() {
		return bytesSent;
	}

	/**
	 * @return the bytes received on the connection so far.
	 */
	public long bytesReceived() {
		return bytesReceived;
	}

	/**
	 * @return the bits sent per second since the previous sample, or -1 for
	 * the first sample.
	 */
	public long outgoingBitrate() {
		return outgoingBitrate;
	}

	/**
	 * @return the bits received per second since the previous sample, or -1
	 * for the first sample.
	 */
	public long incomingBitrate() {
		return incomingBitrate;
	}

	/**
	 * @return the share of received packets lost so far, 0 to 1.
	 */
	public double packetLoss() {
		return packetLoss;
	}

	/**
	 * @return the video streams sent.
	 */
	public List<VideoStats> outboundVideo() {
		return outboundVideo;
	}

	/**
	 * @return the video streams received.
	 */
	public List<VideoStats> inboundVideo() {
		return inboundVideo;
	}

	/**
	 * @return the whole report the values were taken from.
	 */
	public RTCStatsReport report() {
		return report;
	}

	@Override
	public String toString() {
		return "PeerStats[candidate=" + candidateType + ", rtt=" + roundTripTime
				+ ", sent=" + bytesSent + " B, received=" + bytesReceived
				+ " B, out=" + outgoingBitrate + " bps, in=" + incomingBitrate
				+ " bps, loss=" + packetLoss + ", outboundVideo=" + outboundVideo
				+ ", inboundVideo=" + inboundVideo + "]";
	}
}
