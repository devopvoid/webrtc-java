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

package dev.onvoid.webrtc.media.player;

import java.time.Duration;

import dev.onvoid.webrtc.internal.Timeouts;

/**
 * How a {@link MediaReader} opens its source: how long each operation on it
 * may wait, and how much of it FFmpeg reads to find out what its streams
 * hold.
 * <pre>{@code
 * MediaReaderOptions options = MediaReaderOptions.builder()
 *         .analyzeDuration(Duration.ofSeconds(15))
 *         .build();
 *
 * MediaReader reader = new MediaReader("rtsp://camera.local/stream1", options);
 * }</pre>
 * <p>
 * The analysis matters for live streams. FFmpeg reads 5 seconds of a source,
 * and up to 5 MB, to learn the size of its video, among others. A camera that
 * says how large its pictures are only with each keyframe, and sends one less
 * often than that, leaves the size unknown; {@link MediaInfo} then reports
 * 0x0. A longer analysis finds it, and takes that much longer to open a live
 * stream, which sends in real time.
 *
 * @author Alex Andres
 */
public final class MediaReaderOptions {

	private final Duration timeout;

	private final Duration analyzeDuration;

	private final long probeSize;


	private MediaReaderOptions(Builder builder) {
		this.timeout = builder.timeout;
		this.analyzeDuration = builder.analyzeDuration;
		this.probeSize = builder.probeSize;
	}

	/**
	 * Returns a builder with the default options: a timeout of
	 * {@link MediaReader#DEFAULT_TIMEOUT}, and the analysis FFmpeg chooses.
	 *
	 * @return A builder.
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * @return How long opening, and later any single read, seek or close, may
	 * wait on the source. {@link Duration#ZERO} waits as long as it takes.
	 */
	public Duration getTimeout() {
		return timeout;
	}

	/**
	 * @return How much of the source FFmpeg reads to find out what its streams
	 * hold, or {@code null} for the time FFmpeg chooses.
	 */
	public Duration getAnalyzeDuration() {
		return analyzeDuration;
	}

	/**
	 * @return How many bytes of the source FFmpeg reads at most to find out
	 * what its streams hold, or 0 for its default.
	 */
	public long getProbeSize() {
		return probeSize;
	}

	@Override
	public String toString() {
		return "MediaReaderOptions[timeout=" + timeout + ", analyzeDuration=" + analyzeDuration
				+ ", probeSize=" + probeSize + "]";
	}


	/**
	 * Collects the options of a reader. Each option not set keeps its default.
	 */
	public static final class Builder {

		/**
		 * The least FFmpeg reads of a source, in bytes, as smaller amounts
		 * cannot tell a format.
		 */
		private static final long MIN_PROBE_SIZE = 32;

		private Duration timeout = MediaReader.DEFAULT_TIMEOUT;

		private Duration analyzeDuration;

		private long probeSize;


		private Builder() {
		}

		/**
		 * Sets how long opening, and later any single read, seek or close,
		 * may wait on the source.
		 *
		 * @param timeout {@link Duration#ZERO} waits as long as it takes; a
		 *                positive timeout waits at least a microsecond.
		 *
		 * @return This builder.
		 *
		 * @throws IllegalArgumentException if the timeout is negative.
		 */
		public Builder timeout(Duration timeout) {
			this.timeout = Timeouts.requireNonNegative(timeout, "timeout");
			return this;
		}

		/**
		 * Sets how much of the source FFmpeg reads to find out what its
		 * streams hold, for one whose keyframes come further apart than the 5
		 * seconds it reads otherwise. Opening a live stream may take this
		 * long on top of the timeout, as the stream sends in real time.
		 *
		 * @param duration More than 0.
		 *
		 * @return This builder.
		 *
		 * @throws IllegalArgumentException if the duration is not positive.
		 */
		public Builder analyzeDuration(Duration duration) {
			this.analyzeDuration = Timeouts.requirePositive(duration, "analyze duration");
			return this;
		}

		/**
		 * Sets how many bytes of the source FFmpeg reads at most to find out
		 * what its streams hold, which is 5,000,000 otherwise. Raise it with
		 * the {@link #analyzeDuration(Duration) analysis duration} for a
		 * stream of a high bit rate, which may reach this first.
		 *
		 * @param bytes At least 32.
		 *
		 * @return This builder.
		 *
		 * @throws IllegalArgumentException if the size is less than 32.
		 */
		public Builder probeSize(long bytes) {
			if (bytes < MIN_PROBE_SIZE) {
				throw new IllegalArgumentException("The probe size must be at least "
						+ MIN_PROBE_SIZE + " bytes, got " + bytes);
			}

			this.probeSize = bytes;
			return this;
		}

		/**
		 * @return The options.
		 */
		public MediaReaderOptions build() {
			return new MediaReaderOptions(this);
		}
	}

}
