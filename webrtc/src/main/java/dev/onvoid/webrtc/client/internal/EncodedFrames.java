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

import dev.onvoid.webrtc.client.RemoteTrack;
import dev.onvoid.webrtc.client.Sender;

/**
 * Lets a native extension, such as the media module's recorder, observe the
 * encoded frames of a client API sender or remote track. Not API for
 * applications: the package is exported to the media module only.
 * <p>
 * The client API keeps its native senders and receivers to itself, and uses
 * them on the control executor of their peer. What this hands out keeps to
 * that: the handle is taken there, and key frames are asked for there, once
 * the sender or receiver is gone not at all.
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
	 * Where a sender's or receiver's encoded frames come from.
	 */
	public static final class Source {

		private final long frames;

		private final Runnable keyFrameRequest;


		/**
		 * @param frames          the handle of {@code NativeApi.encodedFramesOf},
		 *                        whose reference the receiver of this source
		 *                        drops.
		 * @param keyFrameRequest asks for a key frame, from any thread.
		 */
		public Source(long frames, Runnable keyFrameRequest) {
			this.frames = frames;
			this.keyFrameRequest = requireNonNull(keyFrameRequest, "keyFrameRequest");
		}

		/**
		 * @return the handle of the encoded frames, with a reference to drop.
		 */
		public long frames() {
			return frames;
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
