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

package dev.onvoid.webrtc.client.transfer;

/**
 * How {@link FileTransfers} sends and receives.
 * <pre>{@code
 * TransferOptions.builder().window(8 * 1024 * 1024).build();
 * }</pre>
 */
public final class TransferOptions {

	/** The largest chunk, so that a DATA frame fits in 256 KiB, WebRTC's message size limit. */
	static final int MAX_CHUNK_SIZE = 256 * 1024 - Frames.DATA_HEADER;

	static final int MIN_CHUNK_SIZE = 1024;

	static final int MAX_WINDOW = 64 * 1024 * 1024;

	private static final TransferOptions DEFAULTS = builder().build();

	final int chunkSize;

	final int window;

	final boolean resumable;


	private TransferOptions(Builder builder) {
		this.chunkSize = builder.chunkSize;
		this.window = builder.window;
		this.resumable = builder.resumable;
	}

	/**
	 * @return the default options.
	 */
	public static TransferOptions defaults() {
		return DEFAULTS;
	}

	/**
	 * @return a builder with the default options.
	 */
	public static Builder builder() {
		return new Builder();
	}



	/** Builds {@link TransferOptions}. */
	public static final class Builder {

		private int chunkSize = 32 * 1024;

		private int window = 4 * 1024 * 1024;

		private boolean resumable = true;


		private Builder() {
		}

		/**
		 * Sets how much of a file one message carries. The default, 32 KiB,
		 * fits every WebRTC implementation.
		 *
		 * @param bytes from 1 KiB to 256 KiB less 25 bytes of header.
		 *
		 * @return this builder.
		 */
		public Builder chunkSize(int bytes) {
			if (bytes < MIN_CHUNK_SIZE || bytes > MAX_CHUNK_SIZE) {
				throw new IllegalArgumentException("The chunk size must be between " + MIN_CHUNK_SIZE + " and "
						+ MAX_CHUNK_SIZE + ", got " + bytes);
			}

			this.chunkSize = bytes;
			return this;
		}

		/**
		 * Sets how many bytes of a file the remote peer may send before this
		 * side confirmed them, which bounds the memory received data takes
		 * while it waits to be written. A transfer moves at most a window per
		 * round trip, so 4 MiB, the default, carry 40 MiB/s at a round trip of
		 * 100 ms.
		 * <p>
		 * Keep it below the peer's {@link
		 * dev.onvoid.webrtc.client.PeerOptions.Builder#channelReceiveLimit(long)},
		 * 16 MiB by default, or a slow disk may make the channel close.
		 *
		 * @param bytes from 1 KiB to 64 MiB.
		 *
		 * @return this builder.
		 */
		public Builder window(int bytes) {
			if (bytes < MIN_CHUNK_SIZE || bytes > MAX_WINDOW) {
				throw new IllegalArgumentException("The window must be between " + MIN_CHUNK_SIZE + " and "
						+ MAX_WINDOW + ", got " + bytes);
			}

			this.window = bytes;
			return this;
		}

		/**
		 * Sets whether a received file whose transfer was interrupted keeps
		 * its partial data, so that the sender can resume it. On by default.
		 * When off, the partial file is deleted on any failure and an old one
		 * is never continued.
		 *
		 * @param resumable false to start each transfer over.
		 *
		 * @return this builder.
		 */
		public Builder resumable(boolean resumable) {
			this.resumable = resumable;
			return this;
		}

		/**
		 * @return the options.
		 */
		public TransferOptions build() {
			return new TransferOptions(this);
		}
	}
}
