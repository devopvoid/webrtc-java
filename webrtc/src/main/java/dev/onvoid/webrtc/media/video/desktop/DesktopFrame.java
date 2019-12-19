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

import java.awt.Dimension;
import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.util.StringJoiner;

public class DesktopFrame {

	/**
	 * The rectangle in full desktop coordinates.
	 */
	public final Rectangle frameRect;

	/**
	 * The size of the frame in full desktop coordinate space.
	 */
	public final Dimension frameSize;

	/**
	 * The scale factor from DIPs to physical pixels of the frame.
	 */
	public final float scale;

	/**
	 * Distance in the buffer between two neighboring rows in bytes.
	 */
	public final int stride;

	/**
	 * The underlying frame buffer.
	 */
	public final ByteBuffer buffer;


	public DesktopFrame(Rectangle frameRect, Dimension frameSize, float scale, int stride, ByteBuffer buffer) {
		this.frameRect = frameRect;
		this.frameSize = frameSize;
		this.scale = scale;
		this.stride = stride;
		this.buffer = buffer;
	}

	@Override
	public String toString() {
		return new StringJoiner(", ",
				DesktopFrame.class.getSimpleName() + "[", "]")
				.add("frameRect=" + frameRect)
				.add("frameSize=" + frameSize)
				.add("scale=" + scale)
				.add("stride=" + stride)
				.add("buffer=" + buffer)
				.toString();
	}
}
