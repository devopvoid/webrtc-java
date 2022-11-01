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

import dev.onvoid.webrtc.media.FourCC;

import java.nio.ByteBuffer;

public final class VideoBufferConverter {

	public static void convertFromI420(VideoFrameBuffer src, byte[] dst, FourCC fourCC) throws Exception {
		if (src == null) {
			throw new NullPointerException("Source buffer must not be null");
		}
		if (dst == null) {
			throw new NullPointerException("Destination buffer must not be null");
		}
		
		I420Buffer i420 = src.toI420();
		
		I420toByteArray(
				i420.getDataY(), i420.getStrideY(),
				i420.getDataU(), i420.getStrideU(),
				i420.getDataV(), i420.getStrideV(),
				dst,
				i420.getWidth(), i420.getHeight(),
				fourCC.value());
		
		i420.release();
	}
	
	public static void convertFromI420(VideoFrameBuffer src, ByteBuffer dst, FourCC fourCC) throws Exception {
		if (src == null) {
			throw new NullPointerException("Source buffer must not be null");
		}
		if (dst == null) {
			throw new NullPointerException("Destination buffer must not be null");
		}
		
		I420Buffer i420 = src.toI420();
		
		if (dst.isDirect()) {
			I420toDirectBuffer(
					i420.getDataY(), i420.getStrideY(),
					i420.getDataU(), i420.getStrideU(),
					i420.getDataV(), i420.getStrideV(),
					dst,
					i420.getWidth(), i420.getHeight(),
					fourCC.value());
		}
		else {
			byte[] arrayBuffer = null;

			if (dst.hasArray()) {
				arrayBuffer = dst.array();
			}
			else {
				arrayBuffer = new byte[dst.remaining()];
				dst.get(arrayBuffer);
			}

			I420toByteArray(
					i420.getDataY(), i420.getStrideY(),
					i420.getDataU(), i420.getStrideU(),
					i420.getDataV(), i420.getStrideV(),
					arrayBuffer,
					i420.getWidth(), i420.getHeight(),
					fourCC.value());
		}
		
		i420.release();
	}

	private native static void I420toByteArray(
			ByteBuffer srcY, int srcStrideY,
			ByteBuffer srcU, int srcStrideU,
			ByteBuffer srcV, int srcStrideV,
			byte[] dst,
			int width, int height,
			int fourCC) throws Exception;
	
	private native static void I420toDirectBuffer(
			ByteBuffer srcY, int srcStrideY,
			ByteBuffer srcU, int srcStrideU,
			ByteBuffer srcV, int srcStrideV,
			ByteBuffer dst,
			int width, int height,
			int fourCC) throws Exception;

}
