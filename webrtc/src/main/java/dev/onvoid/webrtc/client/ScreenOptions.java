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

import static java.util.Objects.requireNonNull;

import dev.onvoid.webrtc.media.video.desktop.DesktopSource;

/**
 * Which screen or window to capture and how.
 * <pre>{@code
 * ScreenOptions.screen(devices.screens().get(0)).frameRate(15).build();
 * }</pre>
 */
public final class ScreenOptions {

	final DesktopSource source;

	final boolean window;

	final int frameRate;

	final int maxWidth;

	final int maxHeight;


	private ScreenOptions(Builder builder) {
		this.source = builder.source;
		this.window = builder.window;
		this.frameRate = builder.frameRate;
		this.maxWidth = builder.maxWidth;
		this.maxHeight = builder.maxHeight;
	}

	/**
	 * @param screen one of {@link Devices#screens()}.
	 *
	 * @return a builder that captures the screen at 30 frames per second.
	 */
	public static Builder screen(DesktopSource screen) {
		return new Builder(requireNonNull(screen, "screen"), false);
	}

	/**
	 * @param window one of {@link Devices#windows()}.
	 *
	 * @return a builder that captures the window at 30 frames per second.
	 */
	public static Builder window(DesktopSource window) {
		return new Builder(requireNonNull(window, "window"), true);
	}



	/** Builds {@link ScreenOptions}. */
	public static final class Builder {

		private final DesktopSource source;

		private final boolean window;

		private int frameRate = 30;

		private int maxWidth;

		private int maxHeight;


		private Builder(DesktopSource source, boolean window) {
			this.source = source;
			this.window = window;
		}

		/**
		 * @param frameRate the frames per second to capture.
		 *
		 * @return this builder.
		 */
		public Builder frameRate(int frameRate) {
			if (frameRate <= 0) {
				throw new IllegalArgumentException("The frame rate must be positive, got " + frameRate);
			}

			this.frameRate = frameRate;
			return this;
		}

		/**
		 * Scales captured frames down to fit, keeping their aspect ratio.
		 *
		 * @param width  the largest width, in pixels.
		 * @param height the largest height, in pixels.
		 *
		 * @return this builder.
		 */
		public Builder maxSize(int width, int height) {
			if (width <= 0 || height <= 0) {
				throw new IllegalArgumentException("Invalid size " + width + "x" + height);
			}

			this.maxWidth = width;
			this.maxHeight = height;
			return this;
		}

		/**
		 * @return the options.
		 */
		public ScreenOptions build() {
			return new ScreenOptions(this);
		}
	}
}
