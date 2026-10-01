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
 * The built-in video decoders, with the decoders of the GPU in front of them
 * where the platform has them. Set it on a {@link
 * dev.onvoid.webrtc.PeerConnectionFactory PeerConnectionFactory} to decode in
 * hardware:
 * <pre>{@code
 * PeerConnectionFactory factory = PeerConnectionFactory.builder()
 *     .setVideoDecoderFactory(new HardwareVideoDecoderFactory())
 *     .build();
 * }</pre>
 * <p>
 * On Windows, H.264 and AV1 are decoded on the GPU through Direct3D 11 by the
 * Media Foundation decoders of Windows, where the GPU decodes the codec.
 * Decoded frames are copied back to system memory, which WebRTC's frames are
 * in, so hardware decoding saves CPU mostly at high resolutions. A hardware
 * decoder that fails to start, or fails while decoding, is replaced by the
 * software decoder of the same codec, which starts with the next key frame.
 * Where there is no hardware decoder, this factory decodes like a {@link
 * DefaultVideoDecoderFactory}. On macOS, that already uses VideoToolbox.
 * Linux has no hardware decoders yet.
 * <p>
 * The hardware decoders take over only codecs the software decoders have too,
 * so the supported codecs are the same as those of a {@link
 * DefaultVideoDecoderFactory}, and what a peer connection negotiates does not
 * depend on the GPU.
 *
 * @author Alex Andres
 */
public final class HardwareVideoDecoderFactory implements VideoDecoderFactory {

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
	 * Creates a factory for the hardware video decoders.
	 */
	public HardwareVideoDecoderFactory() {
		supportedCodecs = Collections.unmodifiableList(
				Arrays.asList(getSupportedCodecsInternal()));
	}

	@Override
	public List<VideoCodecInfo> getSupportedCodecs() {
		return supportedCodecs;
	}

	/**
	 * Returns a built-in decoder for the given codec, decoding on the GPU if
	 * it can.
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
				return new NativeVideoDecoder(info, true);
			}
		}

		return null;
	}

	private static native VideoCodecInfo[] getSupportedCodecsInternal();

}
