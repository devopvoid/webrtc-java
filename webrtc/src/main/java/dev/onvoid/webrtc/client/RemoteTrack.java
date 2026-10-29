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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import dev.onvoid.webrtc.RTCEncodedFrameTransformer;

/**
 * A track the remote peer sends, handed to {@link Peer#onTrack}. It ends when
 * the remote peer stops sending it or the peer closes.
 */
public interface RemoteTrack {

	/**
	 * @return the track id.
	 */
	String id();

	/**
	 * @return audio or video.
	 */
	MediaKind kind();

	/**
	 * @return the streams the remote peer added the track to.
	 */
	List<String> streamIds();

	/**
	 * @return true for a video track.
	 */
	default boolean isVideo() {
		return kind() == MediaKind.VIDEO;
	}

	/**
	 * @return true for an audio track.
	 */
	default boolean isAudio() {
		return kind() == MediaKind.AUDIO;
	}

	/**
	 * @return this track as a video track.
	 *
	 * @throws IllegalStateException for an audio track.
	 */
	RemoteVideo asVideo();

	/**
	 * @return this track as an audio track.
	 *
	 * @throws IllegalStateException for a video track.
	 */
	RemoteAudio asAudio();

	/**
	 * Sets the transform that every encoded frame of this track passes
	 * through, after the depacketizer and before the decoder, in place of the
	 * one set before, such as the {@link PeerOptions.Builder#receiveTransform
	 * receive transform} of the peer. Frames that arrive before the future
	 * completes pass the one before; set the peer's receive transform to have
	 * it in place from the first frame.
	 * <p>
	 * The transform runs on a thread of this track's receiver, not on the
	 * peer's event thread; see {@link RTCEncodedFrameTransformer}. A frame it
	 * throws on is dropped, and the first such failure reaches the peer's
	 * {@code onError} handlers.
	 *
	 * @param transform the transform, or null to receive frames unchanged.
	 *
	 * @return a future that completes once the transform is set, and fails
	 * with an {@link IllegalStateException} once the track ended.
	 */
	CompletableFuture<Void> transform(RTCEncodedFrameTransformer transform);

	/**
	 * @return true when the remote peer sends no media on the track for now.
	 */
	boolean isMuted();

	/**
	 * Calls the handler when the track is muted or unmuted.
	 *
	 * @param handler called on the peer's event thread with the new value.
	 *
	 * @return the subscription that removes the handler.
	 */
	Subscription onMute(Consumer<Boolean> handler);

	/**
	 * Calls the handler once the track ended. A handler added after that is
	 * called at once.
	 *
	 * @param handler called on the peer's event thread.
	 *
	 * @return the subscription that removes the handler.
	 */
	Subscription onEnded(Runnable handler);

	/**
	 * @return true once the track ended.
	 */
	boolean isEnded();

}
