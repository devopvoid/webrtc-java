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

	public native void start();

	public native void stop();

	public void dispose() {
		detachVideoProcessor();
		disposeInternal();
	}

	private native void initialize();

	private native void setFrameRateInternal(int frameRate);

	private native void disposeInternal();

}
