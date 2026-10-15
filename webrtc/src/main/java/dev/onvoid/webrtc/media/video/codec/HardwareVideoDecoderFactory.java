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
 * to libvpx and dav1d. On the GPUs of Intel and AMD, VP9 (profile 0) and
 * H.265 (Main profile) are decoded with VA-API, where the driver decodes them.
 * <p>
 * The supported codecs are those of a {@link DefaultVideoDecoderFactory}, in
 * the same order. On Windows and Linux, H.264 High 4:4:4 Predictive,
 * which GPU decoders do not take, is decoded in software. H.265 comes after them, only where the hardware decodes it:
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

	private final List<VideoCodecInfo> hardwareCodecs;


	/**
	 * Creates a factory for the hardware video decoders.
	 */
	public HardwareVideoDecoderFactory() {
		VideoCodecInfo[][] codecs = getCodecsInternal();

		supportedCodecs = Collections.unmodifiableList(Arrays.asList(codecs[0]));
		hardwareCodecs = Collections.unmodifiableList(Arrays.asList(codecs[1]));
	}

	@Override
	public List<VideoCodecInfo> getSupportedCodecs() {
		return supportedCodecs;
	}

	/**
	 * Returns the supported codecs that are decoded on the GPU, in the order
	 * of {@link #getSupportedCodecs()}. A codec counts when a hardware decoder
	 * of the machine takes it; on macOS that includes H.264, which
	 * VideoToolbox decodes. The other supported codecs are decoded in
	 * software. The hardware is probed once per process, and again only for
	 * a new setting of the system properties that turn hardware codecs on, so
	 * a GPU or driver that changes later is seen by a new process only.
	 * <p>
	 * A hardware decoder is tried first, not guaranteed: one that fails to
	 * start, or fails while decoding, is replaced by the software decoder of
	 * the same codec, and streams the hardware cannot decode, such as VP9 and AV1 with
	 * spatial layers, are decoded in software. The stats of a running
	 * connection tell which decoder a stream uses, in the
	 * {@code powerEfficientDecoder} attribute of its inbound RTP stats.
	 *
	 * @return The hardware codecs, unmodifiable; empty if the machine has no
	 *         hardware decoder this library supports.
	 */
	public List<VideoCodecInfo> getHardwareCodecs() {
		return hardwareCodecs;
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

	/**
	 * Returns the supported codecs and the hardware codecs, from one native
	 * factory, as creating one probes the hardware.
	 */
	private static native VideoCodecInfo[][] getCodecsInternal();

}
