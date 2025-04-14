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

import dev.onvoid.webrtc.internal.DisposableNativeObject;

import java.util.List;

/**
 * Abstract base class for desktop capture functionality.
 * <p>
 * The DesktopCapturer provides the core functionality for capturing content from desktop sources
 * like screens and windows. It leverages native implementation through JNI for efficient capture
 * operations.
 * <p>
 * This class handles source selection, configuration of capture parameters like frame rate
 * and focus behavior, and the capture process itself.
 * <p>
 * Implementations must provide the concrete native functionality for different platforms.
 *
 * @see ScreenCapturer
 * @see WindowCapturer
 *
 * @author Alex Andres
 */
public abstract class DesktopCapturer extends DisposableNativeObject {

	/**
	 * Represents the result of a desktop capture operation.
	 * This enum defines possible outcomes when attempting to capture a desktop frame,
	 * indicating success or different types of failures that may occur during the capture process.
	 */
	public enum Result {

		/**
		 * The frame was captured successfully.
		 */
		SUCCESS,

		/**
		 * There was a temporary error. The caller should continue calling
		 * CaptureFrame(), in the expectation that it will eventually recover.
		 */
		ERROR_TEMPORARY,

		/**
		 * Capture has failed and will keep failing if the caller tries calling
		 * CaptureFrame() again.
		 */
		ERROR_PERMANENT,

		/**
		 * ERROR_PERMANENT
		 */
		MAX_VALUE;
	}


	/**
	 * The DesktopCapturer doesn't take ownership of the callback. When the
	 * DesktopCapturer is disposed, the callback will be disposed using this
	 * handle.
	 */
	private long callbackHandle;


	@Override
	public native void dispose();

	/**
	 * Retrieves a list of available desktop sources that can be captured.
	 *
	 * @return A list of desktop sources (screens, windows).
	 */
	public native List<DesktopSource> getDesktopSources();

	/**
	 * Selects a specific desktop source for capturing.
	 *
	 * @param source The desktop source to be captured.
	 */
	public native void selectSource(DesktopSource source);

	/**
	 * Sets whether the selected source should be focused during capture.
	 *
	 * @param focus True to focus the selected source, false otherwise.
	 */
	public native void setFocusSelectedSource(boolean focus);

	/**
	 * Sets the maximum frame rate for the desktop capture.
	 *
	 * @param maxFrameRate The maximum number of frames to capture per second.
	 */
	public native void setMaxFrameRate(int maxFrameRate);

	/**
	 * Starts the desktop capture process with the provided callback.
	 *
	 * @param callback The callback that receives capture events and frames.
	 */
	public native void start(DesktopCaptureCallback callback);

	/**
	 * Captures a single frame manually.
	 * The capture result will be delivered via the callback provided in {@link #start}.
	 */
	public native void captureFrame();

}
