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

import java.util.Objects;

import dev.onvoid.webrtc.media.video.VideoDevice;

/**
 * Which camera to open and how. The camera's supported capability nearest to
 * the requested resolution and frame rate is used.
 * <pre>{@code
 * CameraOptions.builder().device(camera).resolution(1280, 720).frameRate(30).build();
 * }</pre>
 */
public final class CameraOptions {

	final VideoDevice device;

	final int width;

	final int height;

	final int frameRate;


	private CameraOptions(Builder builder) {
		this.device = builder.device;
		this.width = builder.width;
		this.height = builder.height;
		this.frameRate = builder.frameRate;
	}

	CameraOptions withDevice(VideoDevice camera) {
		Builder builder = new Builder();
		builder.device = camera;
		builder.width = width;
		builder.height = height;
		builder.frameRate = frameRate;

		return new CameraOptions(builder);
	}

	/**
	 * @return a builder for the first camera with its first capability.
	 */
	public static Builder builder() {
		return new Builder();
	}

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof CameraOptions)) {
			return false;
		}

		CameraOptions other = (CameraOptions) obj;

		return Objects.equals(device, other.device) && width == other.width
				&& height == other.height && frameRate == other.frameRate;
	}

	@Override
	public int hashCode() {
		return Objects.hash(device, width, height, frameRate);
	}

	@Override
	public String toString() {
		return "CameraOptions[device=" + (device == null ? "first" : device.getName())
				+ ", " + width + "x" + height + ", " + frameRate + " fps]";
	}



	/** Builds {@link CameraOptions}. */
	public static final class Builder {

		private VideoDevice device;

		private int width;

		private int height;

		private int frameRate;


		private Builder() {
		}

		/**
		 * @param device one of {@link Devices#cameras()}, or null for the
		 *               first.
		 *
		 * @return this builder.
		 */
		public Builder device(VideoDevice device) {
			this.device = device;
			return this;
		}

		/**
		 * @param width  the width wanted, in pixels.
		 * @param height the height wanted, in pixels.
		 *
		 * @return this builder.
		 */
		public Builder resolution(int width, int height) {
			if (width <= 0 || height <= 0) {
				throw new IllegalArgumentException("Invalid resolution " + width + "x" + height);
			}

			this.width = width;
			this.height = height;
			return this;
		}

		/**
		 * @param frameRate the frame rate wanted.
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
		 * @return the options.
		 */
		public CameraOptions build() {
			return new CameraOptions(this);
		}
	}
}
