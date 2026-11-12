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

package dev.onvoid.webrtc.media.video;

public class VideoDesktopSource extends VideoTrackSource {

	/**
	 * The highest frame rate. The capture thread paces itself in whole
	 * milliseconds, so anything higher would make it spin without a pause.
	 */
	public static final int MAX_FRAME_RATE = 1000;


	public VideoDesktopSource() {
		super();

		initialize();
	}

	public native void setSourceId(long sourceId, boolean isWindow);

	/**
	 * Sets the frames per second to capture, read when the capture starts.
	 *
	 * @param frameRate The frame rate, 1 to {@value #MAX_FRAME_RATE}.
	 *
	 * @throws IllegalArgumentException If the frame rate is out of range.
	 */
	public void setFrameRate(int frameRate) {
		if (frameRate < 1 || frameRate > MAX_FRAME_RATE) {
			throw new IllegalArgumentException(
					"The frame rate must be between 1 and " + MAX_FRAME_RATE + ", got " + frameRate);
		}

		setFrameRateInternal(frameRate);
	}

	/**
	 * Scales captured frames down to fit both limits, keeping their aspect
	 * ratio.
	 *
	 * @param width  The largest width, in pixels.
	 * @param height The largest height, in pixels.
	 */
	public native void setMaxFrameSize(int width, int height);

	/**
	 * Lets the user choose what to share in the operating system's own
	 * picker when the source starts, instead of capturing the screen or window
	 * set with {@link #setSourceId(long, boolean)}. Off by default; read when
	 * the capture starts.
	 * <p>
	 * On macOS 14 and newer this is ScreenCaptureKit's content sharing
	 * picker, which offers a screen or one or more windows. Under Wayland the
	 * desktop portal's picker is used with or without this setting. Where
	 * {@link #isSystemPickerAvailable()} is false, the source ends instead of
	 * capturing.
	 * <p>
	 * {@link #start()} shows the picker and returns at once; no frames arrive
	 * until the user has chosen. If the user cancels the picker, or stops
	 * sharing from the menu bar, the source ends and its tracks call their
	 * {@link dev.onvoid.webrtc.media.MediaStreamTrackEndedListener}s. Show the
	 * picker for one source at a time: sources waiting on it at the same time
	 * all receive the user's choice.
	 *
	 * @param usePicker True to show the system picker when the source starts.
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

	public native void start();

	/**
	 * Stops capturing and waits for the capture to finish. Called from a
	 * listener that the capture invokes, such as an ended listener, it does
	 * not wait.
	 */
	public native void stop();

	/**
	 * Stops capturing, without waiting for the capture to finish, and
	 * releases the source. A track created from the source keeps it, but
	 * receives no more frames.
	 */
	public void dispose() {
		detachVideoProcessor();
		disposeInternal();
	}

	private native void initialize();

	private native void setFrameRateInternal(int frameRate);

	private native void disposeInternal();

}
