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
 * Contains information about a given contributing source (CSRC).
 *
 * @author Alex Andres
 */
public class RTCRtpContributingSource {

	/**
	 * Indicating the most recent time a frame from an RTP packet, originating
	 * from this source, was delivered to the RTCRtpReceiver's MediaStreamTrack.
	 */
	public final long timestamp;

	/**
	 * The CSRC or SSRC identifier of the contributing or synchronization
	 * source.
	 */
	public final long source;

	/**
	 * Only present for audio receivers. This is a value between 0..1 (linear),
	 * where 1.0 represents 0 dBov, 0 represents silence, and 0.5 represents
	 * approximately 6 dBSPL change in the sound pressure level from 0 dBov.
	 */
	public final double audioLevel;

	/**
	 * The last RTP timestamp, as defined in [RFC3550] Section 5.1, of the media
	 * played out at timestamp.
	 */
	public final long rtpTimestamp;


	/**
	 * Creates an instance of RTCRtpContributingSource with the specified
	 * contributing source information.
	 *
	 * @param timestamp    The most recent time a frame from an RTP packet.
	 * @param source       The CSRC or SSRC identifier of the contributing or
	 *                     synchronization source.
	 * @param audioLevel   The sound pressure level. Only present for audio
	 *                     receivers.
	 * @param rtpTimestamp The last RTP timestamp.
	 */
	protected RTCRtpContributingSource(long timestamp, long source,
			double audioLevel, long rtpTimestamp) {
		this.timestamp = timestamp;
		this.source = source;
		this.audioLevel = audioLevel;
		this.rtpTimestamp = rtpTimestamp;
	}
}
