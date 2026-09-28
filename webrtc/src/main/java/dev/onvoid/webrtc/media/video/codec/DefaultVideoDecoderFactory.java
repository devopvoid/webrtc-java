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

import dev.onvoid.webrtc.internal.NativeLoader;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The video decoders built into this library, which a {@link
 * dev.onvoid.webrtc.PeerConnectionFactory PeerConnectionFactory} uses unless
 * it is given a {@link VideoDecoderFactory} of its own. On Windows and Linux
 * these are VP8, VP9, AV1 and H.264 in software; on macOS they are those of
 * WebRTC's default factories, with H.264 through VideoToolbox.
 * {@link #getSupportedCodecs()} lists what the platform has.
 * <p>
 * A factory of one's own can hand out these decoders next to its own ones
 * by delegating to an instance of this class. The decoders it creates are
 * {@link NativeVideoDecoder}s, which run inside WebRTC and are not to be
 * called from Java.
 *
 * @author Alex Andres
 */
public final class DefaultVideoDecoderFactory implements VideoDecoderFactory {

	static {
		try {
			NativeLoader.loadLibrary("webrtc-java");
		}
		catch (Exception e) {
			throw new RuntimeException("Load library 'webrtc-java' failed", e);
		}
	}


	private final List<VideoCodecInfo> supportedCodecs;


	/**
	 * Creates a factory for the built-in video decoders.
	 */
	public DefaultVideoDecoderFactory() {
		supportedCodecs = Collections.unmodifiableList(
				Arrays.asList(getSupportedCodecsInternal()));
	}

	@Override
	public List<VideoCodecInfo> getSupportedCodecs() {
		return supportedCodecs;
	}

	/**
	 * Returns a built-in decoder for the given codec.
	 *
	 * @param info The codec to decode, as negotiated.
	 *
	 * @return A native decoder, or {@code null} if there is no built-in
	 *         decoder for the codec.
	 */
	@Override
	public NativeVideoDecoder createDecoder(VideoCodecInfo info) {
		Objects.requireNonNull(info, "VideoCodecInfo is null");

		for (VideoCodecInfo codec : supportedCodecs) {
			if (codec.getName().equalsIgnoreCase(info.getName())) {
				return new NativeVideoDecoder(info);
			}
		}

		return null;
	}

	private static native VideoCodecInfo[] getSupportedCodecsInternal();

}
