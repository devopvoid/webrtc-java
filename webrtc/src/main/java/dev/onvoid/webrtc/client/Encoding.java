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
 * Values that change how a {@link Sender} encodes. Values left unset keep
 * what the sender has.
 * <pre>{@code
 * sender.configure(Encoding.builder().maxBitrate(1_500_000).maxFrameRate(30).build());
 * }</pre>
 */
public final class Encoding {

	final Boolean active;

	final Integer maxBitrate;

	final Double maxFrameRate;

	final Double scaleDownBy;


	private Encoding(Builder builder) {
		this.active = builder.active;
		this.maxBitrate = builder.maxBitrate;
		this.maxFrameRate = builder.maxFrameRate;
		this.scaleDownBy = builder.scaleDownBy;
	}

	/**
	 * @return a builder with no values set.
	 */
	public static Builder builder() {
		return new Builder();
	}



	/** Builds an {@link Encoding}. */
	public static final class Builder {

		private Boolean active;

		private Integer maxBitrate;

		private Double maxFrameRate;

		private Double scaleDownBy;


		private Builder() {
		}

		/**
		 * @param active false to pause sending without renegotiating.
		 *
		 * @return this builder.
		 */
		public Builder active(boolean active) {
			this.active = active;
			return this;
		}

		/**
		 * @param bitsPerSecond the highest bitrate to send at.
		 *
		 * @return this builder.
		 */
		public Builder maxBitrate(int bitsPerSecond) {
			if (bitsPerSecond <= 0) {
				throw new IllegalArgumentException("The bitrate must be positive, got " + bitsPerSecond);
			}

			this.maxBitrate = bitsPerSecond;
			return this;
		}

		/**
		 * @param framesPerSecond the highest frame rate of a video track.
		 *
		 * @return this builder.
		 */
		public Builder maxFrameRate(double framesPerSecond) {
			if (framesPerSecond <= 0) {
				throw new IllegalArgumentException("The frame rate must be positive, got " + framesPerSecond);
			}

			this.maxFrameRate = framesPerSecond;
			return this;
		}

		/**
		 * @param factor how much smaller than captured a video track is sent,
		 *               1 or more.
		 *
		 * @return this builder.
		 */
		public Builder scaleDownBy(double factor) {
			if (factor < 1) {
				throw new IllegalArgumentException("The factor must be 1 or more, got " + factor);
			}

			this.scaleDownBy = factor;
			return this;
		}

		/**
		 * @return the encoding values.
		 */
		public Encoding build() {
			return new Encoding(this);
		}
	}
}
