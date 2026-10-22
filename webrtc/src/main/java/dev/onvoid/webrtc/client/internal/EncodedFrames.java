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

package dev.onvoid.webrtc.client.internal;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import dev.onvoid.webrtc.client.RemoteTrack;
import dev.onvoid.webrtc.client.Sender;
import dev.onvoid.webrtc.internal.NativeApi;

/**
 * Lets a native extension, such as the media module's recorder, observe the
 * encoded frames of a client API sender or remote track. Not API for
 * applications: the package is exported to the media module only.
 * <p>
 * The client API keeps its native senders and receivers to itself, and uses
 * them on the control executor of their peer. What this hands out keeps to
 * that: the handle is taken there, and key frames are asked for there, once
 * the sender or receiver is gone not at all.
 * <p>
 * The futures of {@link #of(Sender)} and {@link #of(RemoteTrack)} complete
 * in any case, failed once the peer or its runtime is closed. A caller that
 * stops waiting cancels the future and closes a source that completes it all
 * the same, see {@link Source}.
 */
public final class EncodedFrames {

	/** The client API's side, registered by its peers. */
	public interface Access {

		/**
		 * @param sender a sender of the client API.
		 *
		 * @return a future with the source of the sender's encoded frames.
		 */
		CompletableFuture<Source> of(Sender sender);

		/**
		 * @param track a remote track of the client API.
		 *
		 * @return a future with the source of the track's encoded frames.
		 */
		CompletableFuture<Source> of(RemoteTrack track);

	}

	/**
	 * Where a sender's or receiver's encoded frames come from. It owns the
	 * reference of its handle until {@link #take()} hands it over; closing it
	 * before drops the reference, closing it after does nothing. Whoever gets
	 * a source does one or the other, so that no reference is left behind.
	 */
	public static final class Source implements AutoCloseable {

		/** The handle, or 0 once taken or released. */
		private final AtomicLong frames;

		private final Runnable keyFrameRequest;


		/**
		 * @param frames          the handle of {@code NativeApi.encodedFramesOf},
		 *                        whose reference this source takes over.
		 * @param keyFrameRequest asks for a key frame, from any thread.
		 */
		public Source(long frames, Runnable keyFrameRequest) {
			this.frames = new AtomicLong(frames);
			this.keyFrameRequest = requireNonNull(keyFrameRequest, "keyFrameRequest");
		}

		/**
		 * Hands over the handle together with its reference, which the caller
		 * drops from then on.
		 *
		 * @return the handle of the encoded frames.
		 *
		 * @throws IllegalStateException if it was taken or released before.
		 */
		public long take() {
			long handle = frames.getAndSet(0);

			if (handle == 0) {
				throw new IllegalStateException("The encoded frames were taken or released");
			}

			return handle;
		}

		/**
		 * Drops the reference of the handle, unless {@link #take()} handed it
		 * over. Closing it again does nothing.
		 */
		@Override
		public void close() {
			NativeApi.releaseEncodedFrames(frames.getAndSet(0));
		}

		/**
		 * Asks for a key frame. Does nothing once the sender or receiver is
		 * gone.
		 */
		public void requestKeyFrame() {
			keyFrameRequest.run();
		}
	}

	private static volatile Access access;


	private EncodedFrames() {
	}

	/**
	 * Registers the client API's side. The first registration stays.
	 *
	 * @param side the client API's side.
	 */
	public static synchronized void register(Access side) {
		if (access == null) {
			access = requireNonNull(side, "side");
		}
	}

	/**
	 * @param sender a sender of the client API.
	 *
	 * @return a future with the source of the sender's encoded frames.
	 */
	public static CompletableFuture<Source> of(Sender sender) {
		return access().of(requireNonNull(sender, "sender"));
	}

	/**
	 * @param track a remote track of the client API.
	 *
	 * @return a future with the source of the track's encoded frames.
	 */
	public static CompletableFuture<Source> of(RemoteTrack track) {
		return access().of(requireNonNull(track, "track"));
	}

	private static Access access() {
		Access side = access;

		if (side == null) {
			// A sender or track exists, so its peer's class registered.
			throw new IllegalStateException("No peer of the client API was created");
		}

		return side;
	}
}
