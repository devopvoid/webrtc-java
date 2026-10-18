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

import dev.onvoid.webrtc.media.video.VideoFrame;

/**
 * A video track fed with frames the application pushes, made by {@link
 * LocalMedia#customVideo(String)}.
 */
public interface CustomVideo extends LocalVideo {

	/**
	 * Sends a frame, as captured now. The frame is not released.
	 *
	 * @param frame the frame.
	 */
	void push(VideoFrame frame);

	/**
	 * Sends a frame with a timestamp of its own, for sources with timing of
	 * their own, such as a video file. The frame is not released.
	 *
	 * @param frame           the frame.
	 * @param timestampMicros when the frame was captured, in microseconds on
	 *                        a clock of the application.
	 */
	void push(VideoFrame frame, long timestampMicros);

}
