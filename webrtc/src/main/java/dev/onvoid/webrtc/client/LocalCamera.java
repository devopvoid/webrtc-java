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

import java.util.concurrent.CompletableFuture;

import dev.onvoid.webrtc.media.video.VideoDevice;

/**
 * A camera this side sends, made by {@link LocalMedia#camera()}.
 */
public interface LocalCamera extends LocalVideo {

	/**
	 * @return the camera captured now.
	 */
	VideoDevice device();

	/**
	 * Captures another camera, with the same resolution and frame rate, in
	 * this track. Every peer that sends the track keeps sending it, without
	 * renegotiating, and local preview handlers keep getting frames. The
	 * camera captured before is closed unless another track still uses it.
	 *
	 * @param device one of {@link Devices#cameras()}.
	 *
	 * @return a future that completes once every peer sends the new camera. It
	 * fails with a {@link MediaException} if the camera cannot be opened, or is
	 * already open with other options, and the track keeps its camera then.
	 */
	CompletableFuture<Void> switchCamera(VideoDevice device);

}
