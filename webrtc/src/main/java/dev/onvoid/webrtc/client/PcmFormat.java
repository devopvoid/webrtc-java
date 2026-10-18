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

/**
 * The format of 16-bit PCM audio: sample rate and channel count.
 */
public final class PcmFormat {

	private final int sampleRate;

	private final int channels;


	private PcmFormat(int sampleRate, int channels) {
		this.sampleRate = sampleRate;
		this.channels = channels;
	}

	/**
	 * @param sampleRate the sample rate in Hz, a multiple of 100 so that 10 ms
	 *                   hold a whole number of samples.
	 * @param channels   the channel count, 1 to 8.
	 *
	 * @return the format.
	 */
	public static PcmFormat of(int sampleRate, int channels) {
		if (sampleRate < 8000 || sampleRate > 384000 || sampleRate % 100 != 0) {
			throw new IllegalArgumentException("The sample rate must be a multiple of 100 between 8000 and 384000 Hz, got " + sampleRate);
		}
		if (channels < 1 || channels > 8) {
			throw new IllegalArgumentException("The channel count must be between 1 and 8, got " + channels);
		}

		return new PcmFormat(sampleRate, channels);
	}

	/**
	 * @return the sample rate in Hz.
	 */
	public int sampleRate() {
		return sampleRate;
	}

	/**
	 * @return the channel count.
	 */
	public int channels() {
		return channels;
	}

	int framesPer10Ms() {
		return sampleRate / 100;
	}

	int bytesPer10Ms() {
		return framesPer10Ms() * channels * 2;
	}

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof PcmFormat)) {
			return false;
		}

		PcmFormat other = (PcmFormat) obj;

		return sampleRate == other.sampleRate && channels == other.channels;
	}

	@Override
	public int hashCode() {
		return sampleRate * 31 + channels;
	}

	@Override
	public String toString() {
		return "PcmFormat[" + sampleRate + " Hz, " + channels + " channels]";
	}
}
