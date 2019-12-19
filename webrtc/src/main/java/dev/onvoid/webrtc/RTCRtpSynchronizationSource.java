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
 * Contains information about a given synchronization source (SSRC).
 *
 * @author Alex Andres
 */
public class RTCRtpSynchronizationSource extends RTCRtpContributingSource {

	/**
	 * Only present for audio receivers. Whether the last RTP packet, delivered
	 * from this source, contains voice activity (true) or not (false). If the
	 * RFC 6464 extension header was not present, or if the peer has signaled
	 * that it is not using the V bit by setting the "vad" extension attribute
	 * to "off".
	 */
	public final boolean voiceActivityFlag;


	/**
	 * Creates an instance of RTCRtpSynchronizationSource with the specified
	 * synchronization source information.
	 *
	 * @param timestamp         The most recent time a frame from an RTP
	 *                          packet.
	 * @param source            The CSRC or SSRC identifier of the contributing
	 *                          or synchronization source.
	 * @param audioLevel        The sound pressure level. Only present for audio
	 *                          receivers.
	 * @param rtpTimestamp      The last RTP timestamp.
	 * @param voiceActivityFlag Whether the last RTP packet contains voice
	 *                          activity (true) or not (false).
	 */
	protected RTCRtpSynchronizationSource(long timestamp, long source,
			double audioLevel, long rtpTimestamp, boolean voiceActivityFlag) {
		super(timestamp, source, audioLevel, rtpTimestamp);

		this.voiceActivityFlag = voiceActivityFlag;
	}
}
