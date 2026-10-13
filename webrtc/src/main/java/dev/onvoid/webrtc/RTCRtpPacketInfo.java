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

import java.util.Arrays;

/**
 * What an RTP packet that some received audio was assembled from tells about
 * it: who sent it, who contributed to it, and how loud it was.
 *
 * @author Alex Andres
 *
 * @see dev.onvoid.webrtc.media.audio.AudioTrackSink
 */
public class RTCRtpPacketInfo {

	/** The synchronization source (SSRC) of the packet. */
	public final long ssrc;

	/** The contributing sources (CSRCs) of the packet, possibly none. */
	public final long[] contributingSources;

	/** The RTP timestamp of the packet. */
	public final long rtpTimestamp;

	/** The local time the packet was received, in milliseconds. */
	public final long receiveTimeMs;

	/**
	 * The audio level of the packet in -dBov, from 0 (loudest) to 127
	 * (silence), or null if the sender gave none.
	 */
	public final Integer audioLevel;


	/**
	 * Creates an instance of RTCRtpPacketInfo.
	 *
	 * @param ssrc                The synchronization source of the packet.
	 * @param contributingSources The contributing sources of the packet.
	 * @param rtpTimestamp        The RTP timestamp of the packet.
	 * @param receiveTimeMs       The local time the packet was received, in
	 *                            milliseconds.
	 * @param audioLevel          The audio level of the packet in -dBov, or
	 *                            null.
	 */
	public RTCRtpPacketInfo(long ssrc, long[] contributingSources, long rtpTimestamp, long receiveTimeMs,
			Integer audioLevel) {
		this.ssrc = ssrc;
		this.contributingSources = contributingSources;
		this.rtpTimestamp = rtpTimestamp;
		this.receiveTimeMs = receiveTimeMs;
		this.audioLevel = audioLevel;
	}

	@Override
	public String toString() {
		return String.format("%s@%d [ssrc=%d, contributingSources=%s, rtpTimestamp=%d, receiveTimeMs=%d, audioLevel=%s]",
				RTCRtpPacketInfo.class.getSimpleName(), hashCode(), ssrc, Arrays.toString(contributingSources),
				rtpTimestamp, receiveTimeMs, audioLevel);
	}

}
