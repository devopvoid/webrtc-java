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
	 * Lets the user choose what to capture in the operating system's own
	 * picker, instead of the source set with {@link #selectSource}. Off by
	 * default. Set it before {@link #start}; changing it replaces the native
	 * capturer, which forgets a selected source.
	 * <p>
	 * On macOS 14 and newer this is ScreenCaptureKit's content sharing
	 * picker, which offers a screen or one or more windows, for a
	 * {@link ScreenCapturer} and a {@link WindowCapturer} alike. Under
	 * Wayland it is the desktop portal's picker for screens and windows.
	 * Where {@link #isSystemPickerAvailable()} is false, every
	 * {@link #captureFrame()} reports {@link Result#ERROR_PERMANENT} instead
	 * of capturing something the user did not choose.
	 * <p>
	 * {@link #start} shows the picker and returns at once. Until the user
	 * has chosen, {@link #captureFrame()} reports {@link Result#ERROR_TEMPORARY}.
	 * If the user cancels the picker, or stops sharing from the menu bar, it
	 * reports {@link Result#ERROR_PERMANENT}. Show the picker for one capturer
	 * at a time: capturers waiting on it at the same time all receive the
	 * user's choice.
	 *
	 * @param usePicker True to show the system picker when capture starts.
	 *
	 * @throws IllegalStateException If the capturer has been started.
	 */
	public native void setUseSystemPicker(boolean usePicker);

	/**
	 * Whether this system offers a picker for
	 * {@link #setUseSystemPicker(boolean)}: macOS 14 and newer, and Linux
	 * under Wayland.
	 *
	 * @return True if a system picker is available.
	 */
	public static native boolean isSystemPickerAvailable();

	/**
	 * Starts the desktop capture process with the provided callback. A capturer
	 * can be started once and keeps its callback until it is disposed.
	 *
	 * @param callback The callback that receives capture events and frames.
	 *
	 * @throws IllegalStateException If the capturer has been started before.
	 */
	public native void start(DesktopCaptureCallback callback);

	/**
	 * Captures a single frame manually.
	 * The capture result will be delivered via the callback provided in {@link #start}.
	 * Where the platform offers no capture backend, for example on Linux without
	 * a display, the result is {@link Result#ERROR_PERMANENT}.
	 *
	 * @throws IllegalStateException If the capturer has not been started.
	 */
	public native void captureFrame();

}
