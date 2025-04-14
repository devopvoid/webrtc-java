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

/**
 * A capturer for individual windows on the desktop. Allows for capturing specific application windows for streaming
 * or recording.
 *
 * @author Alex Andres
 */
public class WindowCapturer extends DesktopCapturer {

	/**
	 * Constructs a new WindowCapturer instance and initializes the components required for window capturing.
	 */
	public WindowCapturer() {
		initialize();
	}

	/**
	 * Initializes the native implementation of the window capturer.
	 */
	private native void initialize();

}
