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

import dev.onvoid.webrtc.internal.RefCounted;

/**
 * Base class for frame buffers of different types of pixel format and storage.
 */
public interface VideoFrameBuffer extends RefCounted {
	
	/**
	 * Resolution of the buffer in pixels.
	 */
	int getWidth();

	int getHeight();

	/**
	 * Returns a memory-backed frame in I420 format. If the pixel data is in
	 * another format, a conversion will take place. All implementations must
	 * provide a fallback to I420 for compatibility with e.g. the internal
	 * WebRTC software encoders.
	 */
	I420Buffer toI420();

	/**
	 * Crops a region defined by |cropx|, |cropY|, |cropWidth| and |cropHeight|.
	 * Scales it to size |scaleWidth| x |scaleHeight|.
	 */
	VideoFrameBuffer cropAndScale(int cropX, int cropY, int cropWidth,
			int cropHeight, int scaleWidth, int scaleHeight);

}
