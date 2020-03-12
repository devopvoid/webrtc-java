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

import java.util.Objects;

public class VideoCaptureCapability {

	public final int width;

	public final int height;

	public final int frameRate;


	public VideoCaptureCapability(int width, int height, int frameRate) {
		this.width = width;
		this.height = height;
		this.frameRate = frameRate;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}

		VideoCaptureCapability other = (VideoCaptureCapability) o;

		return width == other.width && height == other.height &&
				frameRate == other.frameRate;
	}

	@Override
	public int hashCode() {
		return Objects.hash(width, height, frameRate);
	}

	@Override
	public String toString() {
		return String.format("%s [width=%s, height=%s, frameRate=%s]",
				VideoCaptureCapability.class.getSimpleName(),
				width, height, frameRate);
	}
}
