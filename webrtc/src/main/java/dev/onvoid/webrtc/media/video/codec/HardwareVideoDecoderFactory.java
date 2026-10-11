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
 * On Windows, H.264, H.265 (Main profile), AV1 and VP9 (profile 0) are
 * decoded on the GPU through Direct3D 11 by the Media Foundation decoders of
 * Windows, where the GPU decodes the codec and Windows has a decoder for it;
 * H.265 needs the HEVC Video Extensions and VP9 the VP9 Video Extensions of
 * the Microsoft Store. VP9 streams with spatial layers go to libvpx.
 * Decoded frames are copied back to system memory, which WebRTC's frames are
 * in, so hardware decoding saves CPU mostly at high resolutions. A hardware
 * decoder that fails to start, or fails while decoding, is replaced by the
 * software decoder of the same codec, which starts with the next key frame.
 * On macOS, H.264 is decoded through VideoToolbox by the {@link
 * DefaultVideoDecoderFactory} already. This factory adds the hardware
 * decoders of VideoToolbox for VP9 (profile 0) and H.265 (Main profile), on
 * Macs that have them, and for AV1 (profile 0), on Apple M3 and newer. VP9
 * and AV1 streams they cannot decode, such as those with spatial layers, go
 * to libvpx and dav1d. Where there is no hardware decoder, this factory
 * decodes like a {@link DefaultVideoDecoderFactory}.
 * <p>
 * On Linux, H.264, H.265 (Main profile), VP9 (profile 0) and AV1 (profile 0)
 * are decoded on NVIDIA GPUs with NVDEC, each where the GPU decodes it (AV1
 * since Ampere), loaded from the NVIDIA driver when the factory is made; a
 * machine without the driver is not affected. Streams with spatial layers go
 * to libvpx and dav1d. VP9 (profile 0) is decoded with VA-API on the GPUs of
 * Intel and AMD only with the system property
 * {@code webrtc.hardware.vaapiExperimental} set to {@code true}: the decoder
 * has not been tested on such a GPU yet.
 * <p>
 * The supported codecs are those of a {@link DefaultVideoDecoderFactory}, in
 * the same order. H.265 comes after them, only where the hardware decodes it:
 * there is no software decoder for it to fall back to, and it is never
 * decoded in software, so an H.265 stream the hardware cannot decode is not
 * decoded at all.
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
