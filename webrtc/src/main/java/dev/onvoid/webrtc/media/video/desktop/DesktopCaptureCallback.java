/*
 * Copyright 2019 Alex Andres
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

package dev.onvoid.webrtc.media.video.desktop;

import dev.onvoid.webrtc.media.video.VideoFrame;

/**
 * Callback interface for desktop capture operations. Implementers receive captured frames from a desktop capture
 * source.
 *
 * @author Alex Andres
 */
public interface DesktopCaptureCallback {

	/**
	 * Called when a frame has been captured from the desktop.
	 *
	 * @param result The result of the capture operation.
	 * @param frame  The captured video frame data, if successful.
	 */
	void onCaptureResult(DesktopCapturer.Result result, VideoFrame frame);

}
