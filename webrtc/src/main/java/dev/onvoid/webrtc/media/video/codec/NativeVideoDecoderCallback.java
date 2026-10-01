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

package dev.onvoid.webrtc.media.video.codec;

import dev.onvoid.webrtc.media.video.VideoFrame;

import java.util.Objects;

/**
 * The {@link VideoDecoder.Callback} WebRTC hands to a Java decoder. It passes
 * decoded frames on to the native decoder wrapper until that wrapper
 * invalidates it, when the decoder is released or destroyed, so that a
 * decoder that delivers late cannot reach a wrapper that no longer exists.
 *
 * @author Alex Andres
 */
final class NativeVideoDecoderCallback implements VideoDecoder.Callback {

	/** The native decoder wrapper, 0 once invalidated. */
	private long nativeHandle;


	/**
	 * Created from native code.
	 */
	private NativeVideoDecoderCallback(long nativeHandle) {
		this.nativeHandle = nativeHandle;
	}

	@Override
	public synchronized void onDecodedFrame(VideoFrame frame, Integer decodeTimeMs, Integer qp) {
		Objects.requireNonNull(frame, "VideoFrame is null");

		if (nativeHandle != 0) {
			onDecodedFrame(nativeHandle, frame,
					decodeTimeMs == null ? -1 : decodeTimeMs,
					qp == null ? -1 : qp);
		}
	}

	/**
	 * Detaches this callback from the native wrapper. Once this returns, no
	 * frame is passed on any more, nor is one still being passed on. Called
	 * from native code.
	 */
	@SuppressWarnings("unused")
	private synchronized void invalidate() {
		nativeHandle = 0;
	}

	private static native void onDecodedFrame(long nativeHandle, VideoFrame frame,
			int decodeTimeMs, int qp);

}
