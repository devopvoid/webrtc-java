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

/**
 * A built-in decoder, created by a {@link DefaultVideoDecoderFactory}. It is a
 * placeholder: returned from a {@link VideoDecoderFactory}, it makes WebRTC
 * create the native decoder for its codec, which then runs entirely inside
 * WebRTC. Its methods are therefore not to be called from Java, and throw
 * {@link UnsupportedOperationException}.
 *
 * @author Alex Andres
 */
public final class NativeVideoDecoder implements VideoDecoder {

	/** The codec to create the native decoder for; read by native code. */
	private final VideoCodecInfo codecInfo;


	NativeVideoDecoder(VideoCodecInfo codecInfo) {
		this.codecInfo = codecInfo;
	}

	/**
	 * @return The codec this decoder decodes.
	 */
	public VideoCodecInfo getCodecInfo() {
		return codecInfo;
	}

	@Override
	public VideoCodecStatus initDecode(Settings settings, Callback callback) {
		throw unsupported();
	}

	@Override
	public VideoCodecStatus release() {
		throw unsupported();
	}

	@Override
	public VideoCodecStatus decode(EncodedImage image) {
		throw unsupported();
	}

	@Override
	public String toString() {
		return String.format("%s@%d [codec=%s]",
				NativeVideoDecoder.class.getSimpleName(), hashCode(),
				codecInfo.getName());
	}

	private static UnsupportedOperationException unsupported() {
		return new UnsupportedOperationException("A native decoder runs inside WebRTC only");
	}

}
