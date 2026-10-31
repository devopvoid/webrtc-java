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

package dev.onvoid.webrtc.media.audio;

import static java.util.Objects.requireNonNull;

import java.time.Duration;

/**
 * When a {@link VoiceActivityListener} hears of speech. WebRTC's {@link
 * VoiceActivityDetector} rates the audio with the probability that it holds
 * a voice, from 0 to 1, anew every 30 ms; silence rates 0.01. Speech begins
 * with 10 ms rated at or above the threshold, and ends once the audio stayed
 * below it for longer than the hangover, which bridges the pauses between
 * words.
 * <p>
 * A lower threshold catches quiet speech sooner and takes more noise for
 * speech; a longer hangover ends speech later and splits it less often.
 */
public final class VoiceActivityConfig {

	/** The threshold by default. */
	public static final double DEFAULT_THRESHOLD = 0.5;

	/** The hangover by default. */
	public static final Duration DEFAULT_HANGOVER = Duration.ofMillis(300);

	private final double threshold;

	private final Duration hangover;


	/**
	 * Creates a config with the {@link #DEFAULT_THRESHOLD} and the {@link
	 * #DEFAULT_HANGOVER}.
	 */
	public VoiceActivityConfig() {
		this(DEFAULT_THRESHOLD, DEFAULT_HANGOVER);
	}

	/**
	 * @param threshold the voice probability, above 0 and at most 1, from
	 *                  which on audio counts as speech.
	 * @param hangover  how long speech goes on after the audio fell below the
	 *                  threshold, zero or more; rounded up to whole 10 ms.
	 *
	 * @throws IllegalArgumentException if either is out of range.
	 */
	public VoiceActivityConfig(double threshold, Duration hangover) {
		requireNonNull(hangover, "hangover");

		if (!(threshold > 0 && threshold <= 1)) {
			throw new IllegalArgumentException("The threshold must be above 0 and at most 1, got " + threshold);
		}
		if (hangover.isNegative() || hangover.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0) {
			throw new IllegalArgumentException("The hangover must be zero or more and fit in an int of "
					+ "milliseconds, got " + hangover);
		}

		this.threshold = threshold;
		this.hangover = hangover;
	}

	/**
	 * @return the voice probability from which on audio counts as speech.
	 */
	public double getThreshold() {
		return threshold;
	}

	/**
	 * @return how long speech goes on after the audio fell below the
	 * threshold.
	 */
	public Duration getHangover() {
		return hangover;
	}

	@Override
	public String toString() {
		return "VoiceActivityConfig[threshold=" + threshold + ", hangover=" + hangover + "]";
	}
}
