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

import dev.onvoid.webrtc.media.video.VideoFrame;

/**
 * A video track the remote peer sends.
 */
public interface RemoteVideo extends RemoteTrack {

	/**
	 * Calls the handler with the decoded frames, newest first: when the
	 * handler falls behind, the frames in between are dropped.
	 *
	 * @param handler called on a thread of this subscription. A frame is valid
	 *                while the handler runs; call {@code retain()} to keep it
	 *                longer and {@code release()} when done.
	 *
	 * @return the subscription that stops the frames.
	 */
	Subscription onFrame(Consumer<VideoFrame> handler);

	/**
	 * Calls the handler with the decoded frames.
	 *
	 * @param delivery what happens to frames when the handler falls behind.
	 * @param handler  called on a thread of this subscription. A frame is
	 *                 valid while the handler runs; call {@code retain()} to
	 *                 keep it longer and {@code release()} when done.
	 *
	 * @return the subscription that stops the frames.
	 */
	Subscription onFrame(FrameDelivery delivery, Consumer<VideoFrame> handler);

}
