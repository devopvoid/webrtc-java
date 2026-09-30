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
 * have an AV1 encoder, with NVENC or Media Foundation, as a single layer; a
 * stream that asks for SVC is encoded in software.
 * A hardware encoder that fails to start, for example because the GPU has no
 * encoder sessions left, or fails while encoding, is replaced by the next one
 * in line, and finally by the software encoder of the same codec, so a stream
 * keeps going. Where there is no hardware encoder, this factory encodes like a
 * {@link DefaultVideoEncoderFactory}. On macOS, that already uses
 * VideoToolbox.
 * <p>
 * The hardware encoders take over only codecs the software encoders have too,
 * so the supported codecs are the same as those of a {@link
 * DefaultVideoEncoderFactory}, and what a peer connection negotiates does not
 * depend on the GPU.
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


	/**
	 * Creates a factory for the hardware video encoders.
	 */
	public HardwareVideoEncoderFactory() {
		supportedCodecs = Collections.unmodifiableList(
				Arrays.asList(getSupportedCodecsInternal()));
	}

	@Override
	public List<VideoCodecInfo> getSupportedCodecs() {
		return supportedCodecs;
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

	private static native VideoCodecInfo[] getSupportedCodecsInternal();

}
