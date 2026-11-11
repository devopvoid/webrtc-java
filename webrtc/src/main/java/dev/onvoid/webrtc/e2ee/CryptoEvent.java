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

package dev.onvoid.webrtc.e2ee;

/**
 * A stream of frames that began to fail, as a {@link CryptoListener} hears
 * of it.
 *
 * @author Alex Andres
 */
public final class CryptoEvent {

	private final CryptoFailure failure;

	private final boolean outgoing;

	private final long participant;

	private final int generation;

	private final long ssrc;

	private final String mimeType;


	CryptoEvent(CryptoFailure failure, boolean outgoing, long participant, int generation, long ssrc,
			String mimeType) {
		this.failure = failure;
		this.outgoing = outgoing;
		this.participant = participant;
		this.generation = generation;
		this.ssrc = ssrc;
		this.mimeType = mimeType;
	}

	/**
	 * @return Why the frames are dropped.
	 */
	public CryptoFailure getFailure() {
		return failure;
	}

	/**
	 * @return True for frames to be sent, false for received ones.
	 */
	public boolean isOutgoing() {
		return outgoing;
	}

	/**
	 * Returns the participant the frames belong to: the local one for
	 * outgoing frames, the one the key ID of a received frame names, or -1 if
	 * a received frame has no readable key ID.
	 *
	 * @return The participant ID, or -1.
	 */
	public long getParticipant() {
		return participant;
	}

	/**
	 * @return The key generation the frames are encrypted with or were to be
	 * decrypted with, or -1 if there is none.
	 */
	public int getGeneration() {
		return generation;
	}

	/**
	 * @return The SSRC of the RTP stream, an unsigned 32-bit value.
	 */
	public long getSsrc() {
		return ssrc;
	}

	/**
	 * @return The codec of the frames, e.g. {@code video/VP8}.
	 */
	public String getMimeType() {
		return mimeType;
	}

	@Override
	public String toString() {
		return String.format("%s [%s, participant=%d, generation=%d, ssrc=%d, mimeType=%s]",
				failure, outgoing ? "outgoing" : "incoming", participant, generation, ssrc, mimeType);
	}

}
