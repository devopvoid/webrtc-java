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

import java.util.function.Consumer;

import dev.onvoid.webrtc.media.video.VideoContentHint;
import dev.onvoid.webrtc.media.video.VideoFrame;

/**
 * A video track this side sends.
 */
public interface LocalVideo extends LocalTrack {

	/**
	 * Calls the handler with the frames the track sends, for a local preview.
	 * When the handler falls behind, it gets the newest frame next.
	 *
	 * @param handler called on a thread of this subscription. A frame is valid
	 *                while the handler runs; call {@code retain()} to keep it
	 *                longer and {@code release()} when done.
	 *
	 * @return the subscription that stops the frames.
	 */
	Subscription onFrame(Consumer<VideoFrame> handler);

	/**
	 * @return what the track is hinted to show, {@link
	 * VideoContentHint#DEFAULT} unless set.
	 */
	VideoContentHint contentHint();

	/**
	 * Hints what the track shows, which decides what its senders keep when
	 * the network or the CPU cannot keep up: the frame rate for {@link
	 * VideoContentHint#MOTION}, the resolution for {@link
	 * VideoContentHint#DETAIL} and {@link VideoContentHint#TEXT}. Can be
	 * changed while the track is sent, without renegotiating, as when a video
	 * starts playing in a shared window.
	 * <p>
	 * The hint belongs to the track: it applies on every peer the track is
	 * sent on, and stays when a camera switches. Every sender has taken the
	 * hint once this returns; a change may make a sender reconfigure its
	 * encoder. Waits briefly for a WebRTC thread. Does nothing once the
	 * track ended.
	 *
	 * @param hint what the track shows.
	 */
	void setContentHint(VideoContentHint hint);

}
