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
 * Bitrates and the recent packet loss are computed between the measurement
 * times of two samples, so the first sample a peer takes has none.
 * <p>
 * Bytes and bitrates count all transports of the peer. The candidate type and
 * the round trip time are those of one of them, the {@link #transport()
 * connection in use}; {@link #transports()} has each one's.
 */
public final class PeerStats {

	private final Instant timestamp;

	private final TransportStats transport;

	private final List<TransportStats> transports;

	private final long bytesSent;

	private final long bytesReceived;

	private final long outgoingBitrate;

	private final long incomingBitrate;

	private final double packetLoss;

	private final double recentPacketLoss;

	private final List<VideoStats> outboundVideo;

	private final List<VideoStats> inboundVideo;

	private final RTCStatsReport report;


	PeerStats(Instant timestamp, TransportStats transport, List<TransportStats> transports,
			long bytesSent, long bytesReceived, long outgoingBitrate,
			long incomingBitrate, double packetLoss, double recentPacketLoss,
			List<VideoStats> outboundVideo, List<VideoStats> inboundVideo,
			RTCStatsReport report) {
		this.timestamp = timestamp;
		this.transport = transport;
		this.transports = transports;
		this.bytesSent = bytesSent;
		this.bytesReceived = bytesReceived;
		this.outgoingBitrate = outgoingBitrate;
		this.incomingBitrate = incomingBitrate;
		this.packetLoss = packetLoss;
		this.recentPacketLoss = recentPacketLoss;
		this.outboundVideo = outboundVideo;
		this.inboundVideo = inboundVideo;
		this.report = report;
	}

	/**
	 * @return when WebRTC measured the statistics, the timestamp of the
	 * report.
	 */
	public Instant timestamp() {
		return timestamp;
	}

	/**
	 * The transport whose candidate type and round trip time this sample
	 * reports. It is the only one that has a candidate pair in use, as with
	 * bundled media. Of several, it is the one that carried the most bytes,
	 * sent and received, and of those the one with the lowest id.
	 *
	 * @return the transport, or null while none has a candidate pair in use.
	 */
	public TransportStats transport() {
		return transport;
	}

	/**
	 * @return every transport of the peer, ordered by id.
	 */
	public List<TransportStats> transports() {
		return transports;
	}

	/**
	 * @return the type of the local candidate in use on the {@link
	 * #transport()} transport: {@code host}, {@code srflx}, {@code prflx} or
	 * {@code relay}. Null while not connected.
	 */
	public String candidateType() {
		return transport == null ? null : transport.candidateType();
	}

	/**
	 * @return the current round trip time on the {@link #transport()}
	 * transport, or null while not measured.
	 */
	public Duration roundTripTime() {
		return transport == null ? null : transport.roundTripTime();
	}

	/**
	 * @return the bytes sent on all transports so far.
	 */
	public long bytesSent() {
		return bytesSent;
	}

	/**
	 * @return the bytes received on all transports so far.
	 */
	public long bytesReceived() {
		return bytesReceived;
	}

	/**
	 * @return the bits sent per second since the previous sample, or -1 when
	 * there is none to compare with: for the first sample, after the clock
	 * went back, or after the byte counter started again.
	 */
	public long outgoingBitrate() {
		return outgoingBitrate;
	}

	/**
	 * @return the bits received per second since the previous sample, or -1
	 * when there is none to compare with: for the first sample, after the
	 * clock went back, or after the byte counter started again.
	 */
	public long incomingBitrate() {
		return incomingBitrate;
	}

	/**
	 * @return the share of received packets lost so far, 0 to 1. After a long
	 * session it barely moves; {@link #recentPacketLoss()} shows what happens
	 * now.
	 */
	public double packetLoss() {
		return packetLoss;
	}

	/**
	 * @return the share of the packets lost of those expected since the
	 * previous sample, 0 to 1, and 0 if none were expected. -1 when there is
	 * no sample to compare with: for the first one, or after the clock went
	 * back. A stream whose counters started again counts from zero.
	 */
	public double recentPacketLoss() {
		return recentPacketLoss;
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
		return "PeerStats[candidate=" + candidateType() + ", rtt=" + roundTripTime()
				+ ", sent=" + bytesSent + " B, received=" + bytesReceived
				+ " B, out=" + outgoingBitrate + " bps, in=" + incomingBitrate
				+ " bps, loss=" + packetLoss + ", recentLoss=" + recentPacketLoss
				+ ", transports=" + transports + ", outboundVideo=" + outboundVideo
				+ ", inboundVideo=" + inboundVideo + "]";
	}
}
