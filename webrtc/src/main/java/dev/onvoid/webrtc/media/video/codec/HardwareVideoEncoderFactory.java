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
 * The built-in video encoders, with the encoders of the GPU in front of them
 * where the platform has them. Set it on a {@link
 * dev.onvoid.webrtc.PeerConnectionFactory PeerConnectionFactory} to encode in
 * hardware:
 * <pre>{@code
 * PeerConnectionFactory factory = PeerConnectionFactory.builder()
 *     .setVideoEncoderFactory(new HardwareVideoEncoderFactory())
 *     .build();
 * }</pre>
 * <p>
 * H.264 is encoded on an NVIDIA GPU with NVENC, on Windows and Linux. On
 * other GPUs it is encoded with the Media Foundation encoder of the driver on
 * Windows, and with its VA-API encoder on Linux. AV1 is encoded on GPUs that
 * have an AV1 encoder, with NVENC, Media Foundation or VA-API, as a single
 * layer, or with VA-API in temporal layers too (L1T2, L1T3); a stream that
 * asks for other SVC modes is encoded in software. H.265 (Main profile) is
 * encoded with NVENC or Media Foundation on GPUs that have an H.265 encoder.
 * VP9 (profile 0) is encoded on GPUs that have a VP9 encoder, which are
 * Intel's: with VA-API on Linux, and with Media Foundation on Windows only
 * with the system property {@code webrtc.hardware.vp9Encoder} set to
 * {@code true} when the factory is created, since that encoder has not been
 * tested on such a GPU yet. With VA-API, VP9 is encoded in temporal layers
 * too (L1T2, L1T3). Otherwise VP9 is encoded with libvpx. On Linux, H.265 is encoded with NVENC on NVIDIA
 * GPUs and with VA-API on the GPUs of Intel and AMD whose driver encodes it.
 * A hardware encoder that fails to start, for example because the GPU has no
 * encoder sessions left, or fails while encoding, is replaced by the next one
 * in line, and finally by the software encoder of the same codec, so a stream
 * keeps going. Where there is no hardware encoder, this factory encodes like a
 * {@link DefaultVideoEncoderFactory}. On macOS, that already encodes H.264
 * with VideoToolbox, and this factory adds H.265 (Main profile), encoded by
 * the hardware HEVC encoder of VideoToolbox on Macs that have one.
 * <p>
 * The supported codecs are those of a {@link DefaultVideoEncoderFactory}, in
 * the same order, so the codec a peer connection prefers does not depend on
 * the hardware. H.265 comes after them, only where the hardware encodes it:
 * there is no software encoder for it to fall back to, and it is never
 * encoded in software.
 *
 * @author Alex Andres
 */
public final class HardwareVideoEncoderFactory implements VideoEncoderFactory {

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
	 * Creates a factory for the hardware video encoders.
	 */
	public HardwareVideoEncoderFactory() {
		VideoCodecInfo[][] codecs = getCodecsInternal();

		supportedCodecs = Collections.unmodifiableList(Arrays.asList(codecs[0]));
		hardwareCodecs = Collections.unmodifiableList(Arrays.asList(codecs[1]));
	}

	@Override
	public List<VideoCodecInfo> getSupportedCodecs() {
		return supportedCodecs;
	}

	/**
	 * Returns the supported codecs that are encoded on the GPU, in the order
	 * of {@link #getSupportedCodecs()}. A codec counts when a hardware encoder
	 * of the machine takes it; on macOS that includes H.264, which
	 * VideoToolbox encodes. The other supported codecs are encoded in
	 * software. The hardware is probed once per process, and again only for
	 * a new setting of the system properties that turn hardware codecs on, so
	 * a GPU or driver that changes later is seen by a new process only.
	 * <p>
	 * A hardware encoder is tried first, not guaranteed: one that fails to
	 * start or gives up while encoding is replaced by the software encoder of
	 * the same codec, and a stream that asks for what the hardware does not
	 * do, such as SVC, is encoded in software. The stats of a running
	 * connection tell which encoder a stream uses, in the
	 * {@code powerEfficientEncoder} attribute of its outbound RTP stats.
	 *
	 * @return The hardware codecs, unmodifiable; empty if the machine has no
	 *         hardware encoder this library supports.
	 */
	public List<VideoCodecInfo> getHardwareCodecs() {
		return hardwareCodecs;
	}

	/**
	 * Returns a built-in encoder for the given codec, encoding on the GPU if
	 * it can.
	 *
	 * @param info The codec to encode, as negotiated.
	 *
	 * @return A native encoder, or {@code null} if there is no built-in
	 *         encoder for the codec.
	 */
	@Override
	public NativeVideoEncoder createEncoder(VideoCodecInfo info) {
		Objects.requireNonNull(info, "VideoCodecInfo is null");

		for (VideoCodecInfo codec : supportedCodecs) {
			if (codec.getName().equalsIgnoreCase(info.getName())) {
				return new NativeVideoEncoder(info, true);
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
