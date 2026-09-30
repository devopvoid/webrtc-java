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

/**
 * A built-in encoder, created by a {@link DefaultVideoEncoderFactory} or a
 * {@link HardwareVideoEncoderFactory}. It is a placeholder: returned from a
 * {@link VideoEncoderFactory}, it makes WebRTC create the native encoder for
 * its codec, which then runs entirely inside WebRTC. Its methods are therefore not to be called from Java, and throw
 * {@link UnsupportedOperationException}.
 *
 * @author Alex Andres
 */
public final class NativeVideoEncoder implements VideoEncoder {

	/** The codec to create the native encoder for; read by native code. */
	private final VideoCodecInfo codecInfo;

	/** Whether the encoder may use the GPU; read by native code. */
	private final boolean hardwareAcceleration;


	NativeVideoEncoder(VideoCodecInfo codecInfo, boolean hardwareAcceleration) {
		this.codecInfo = codecInfo;
		this.hardwareAcceleration = hardwareAcceleration;
	}

	/**
	 * @return The codec this encoder encodes.
	 */
	public VideoCodecInfo getCodecInfo() {
		return codecInfo;
	}

	@Override
	public VideoCodecStatus initEncode(Settings settings, Callback callback) {
		throw unsupported();
	}

	@Override
	public VideoCodecStatus release() {
		throw unsupported();
	}

	@Override
	public VideoCodecStatus encode(VideoFrame frame, EncodeInfo info) {
		throw unsupported();
	}

	@Override
	public VideoCodecStatus setRates(RateControlParameters parameters) {
		throw unsupported();
	}

	@Override
	public String toString() {
		return String.format("%s@%d [codec=%s]",
				NativeVideoEncoder.class.getSimpleName(), hashCode(),
				codecInfo.getName());
	}

	private static UnsupportedOperationException unsupported() {
		return new UnsupportedOperationException("A native encoder runs inside WebRTC only");
	}

}
