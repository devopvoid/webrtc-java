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
 * An encoded audio frame, as an {@link RTCEncodedFrameTransformer} sees it.
 *
 * @author Alex Andres
 */
public class RTCEncodedAudioFrame extends RTCEncodedFrame {

	private static final long[] NO_SOURCES = new long[0];

	private final int sequenceNumber;

	private final int audioLevel;

	private final int[] contributingSources;


	/**
	 * Constructor to be used by the native api.
	 */
	RTCEncodedAudioFrame(long handle, int size, long timestamp, long ssrc,
			int payloadType, String mimeType, long captureTimeUs,
			int sequenceNumber, int audioLevel, int[] contributingSources) {
		super(handle, size, timestamp, ssrc, payloadType, mimeType, captureTimeUs);

		this.sequenceNumber = sequenceNumber;
		this.audioLevel = audioLevel;
		this.contributingSources = contributingSources;
	}

	/**
	 * @return The RTP sequence number of a received frame, or -1 for a frame
	 * about to be sent, which has none yet.
	 */
	public int getSequenceNumber() {
		return sequenceNumber;
	}

	/**
	 * Returns the audio level of this frame, in -dBov: 0 is the loudest, 127
	 * digital silence. Received frames carry it only if the audio level
	 * header extension is in use.
	 *
	 * @return The audio level, or -1 if unknown.
	 */
	public int getAudioLevel() {
		return audioLevel;
	}

	/**
	 * @return The CSRCs of the sources mixed into this frame, as unsigned
	 * 32-bit values; empty if none were.
	 */
	public long[] getContributingSources() {
		if (contributingSources == null) {
			return NO_SOURCES;
		}

		long[] sources = new long[contributingSources.length];

		for (int i = 0; i < sources.length; i++) {
			sources[i] = Integer.toUnsignedLong(contributingSources[i]);
		}

		return sources;
	}

	@Override
	public String toString() {
		return String.format("%s@%d [mimeType=%s, size=%d, timestamp=%d, ssrc=%d]",
				getClass().getSimpleName(), hashCode(), getMimeType(),
				getSize(), getTimestamp(), getSsrc());
	}

}
