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

import java.nio.ByteBuffer;

/**
 * Planar I420 pixels with positive dimensions and direct Y, U and V buffers.
 * Each stride must be at least its plane's width. A plane must hold
 * {@code stride * (rows - 1L) + columns} bytes: padding after the last row is
 * not required. Chroma columns and rows are the frame dimensions rounded up
 * to half size. Plane position and limit are ignored; use a slice to expose
 * only the memory belonging to the plane.
 * <p>
 * Read-only planes can be used as sources. Operations that write pixels,
 * such as {@link VideoBufferConverter#convertToI420(byte[], I420Buffer,
 * dev.onvoid.webrtc.media.FourCC)}, require writable destination planes.
 */
public interface I420Buffer extends VideoFrameBuffer {

	/**
	 * Returns a direct ByteBuffer containing Y-plane data. The buffer capacity is
	 * at least {@code getStrideY() * (getHeight() - 1L) + getWidth()} bytes.
	 * The position of the returned buffer is ignored and must be 0. Callers may mutate the ByteBuffer (eg.
	 * through relative-read operations), so implementations must return a new
	 * ByteBuffer or slice for each call.
	 */
	ByteBuffer getDataY();

	/**
	 * Returns a direct ByteBuffer containing U-plane data. The buffer capacity is
	 * large enough for every chroma row, without requiring trailing padding.
	 * The position of the returned buffer is ignored and must be 0. Callers may mutate the ByteBuffer
	 * (eg. through relative-read operations), so implementations must return a new
	 * ByteBuffer or slice for each call.
	 */
	ByteBuffer getDataU();

	/**
	 * Returns a direct ByteBuffer containing V-plane data. The buffer capacity is
	 * large enough for every chroma row, without requiring trailing padding.
	 * The position of the returned buffer is ignored and must be 0. Callers may mutate the ByteBuffer
	 * (eg. through relative-read operations), so implementations must return a new
	 * ByteBuffer or slice for each call.
	 */
	ByteBuffer getDataV();

	int getStrideY();

	int getStrideU();

	int getStrideV();

}
