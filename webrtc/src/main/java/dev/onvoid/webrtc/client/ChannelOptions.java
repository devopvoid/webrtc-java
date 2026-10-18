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

import dev.onvoid.webrtc.RTCDataChannelInit;

/**
 * How a {@link DataChannel} delivers. The default is reliable and ordered.
 * <pre>{@code
 * ChannelOptions.builder().ordered(false).maxRetransmits(0).build();
 * }</pre>
 */
public final class ChannelOptions {

	private final boolean ordered;

	private final int maxRetransmits;

	private final int maxPacketLifeTime;

	private final String protocol;

	private final int negotiatedId;


	private ChannelOptions(Builder builder) {
		this.ordered = builder.ordered;
		this.maxRetransmits = builder.maxRetransmits;
		this.maxPacketLifeTime = builder.maxPacketLifeTime;
		this.protocol = builder.protocol;
		this.negotiatedId = builder.negotiatedId;
	}

	/**
	 * @return a builder for a reliable, ordered channel.
	 */
	public static Builder builder() {
		return new Builder();
	}

	RTCDataChannelInit toInit() {
		RTCDataChannelInit init = new RTCDataChannelInit();
		init.ordered = ordered;
		init.maxRetransmits = maxRetransmits;
		init.maxPacketLifeTime = maxPacketLifeTime;
		init.protocol = protocol;

		if (negotiatedId >= 0) {
			init.negotiated = true;
			init.id = negotiatedId;
		}

		return init;
	}



	/** Builds {@link ChannelOptions}. */
	public static final class Builder {

		private boolean ordered = true;

		private int maxRetransmits = -1;

		private int maxPacketLifeTime = -1;

		private String protocol;

		private int negotiatedId = -1;


		private Builder() {
		}

		/**
		 * @param ordered false to let messages arrive out of order.
		 *
		 * @return this builder.
		 */
		public Builder ordered(boolean ordered) {
			this.ordered = ordered;
			return this;
		}

		/**
		 * Makes the channel unreliable: a message is sent at most this many
		 * more times. Excludes {@link #maxPacketLifeTime(Duration)}.
		 *
		 * @param retransmits 0 or more.
		 *
		 * @return this builder.
		 */
		public Builder maxRetransmits(int retransmits) {
			if (retransmits < 0) {
				throw new IllegalArgumentException("Retransmits must be 0 or more, got " + retransmits);
			}

			this.maxRetransmits = retransmits;
			return this;
		}

		/**
		 * Makes the channel unreliable: a message is retransmitted only for
		 * this long. Excludes {@link #maxRetransmits(int)}.
		 *
		 * @param lifeTime how long a message may be retransmitted.
		 *
		 * @return this builder.
		 */
		public Builder maxPacketLifeTime(Duration lifeTime) {
			long millis = lifeTime.toMillis();

			if (millis < 0 || millis > Integer.MAX_VALUE) {
				throw new IllegalArgumentException("Invalid packet life time " + lifeTime);
			}

			this.maxPacketLifeTime = (int) millis;
			return this;
		}

		/**
		 * @param protocol the subprotocol the remote peer sees.
		 *
		 * @return this builder.
		 */
		public Builder protocol(String protocol) {
			this.protocol = protocol;
			return this;
		}

		/**
		 * Creates the channel without announcing it: both peers create it
		 * with the same id. Such a channel never shows up in {@link
		 * Peer#onChannel}.
		 *
		 * @param id the id both peers use, 0 to 65534.
		 *
		 * @return this builder.
		 */
		public Builder negotiatedId(int id) {
			if (id < 0 || id > 65534) {
				throw new IllegalArgumentException("The id must be between 0 and 65534, got " + id);
			}

			this.negotiatedId = id;
			return this;
		}

		/**
		 * @return the options.
		 *
		 * @throws IllegalStateException if both limits of an unreliable
		 *                               channel are set.
		 */
		public ChannelOptions build() {
			if (maxRetransmits >= 0 && maxPacketLifeTime >= 0) {
				throw new IllegalStateException("A channel is limited by retransmits or by packet life time, not both");
			}

			return new ChannelOptions(this);
		}
	}
}
