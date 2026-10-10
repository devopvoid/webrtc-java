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

package dev.onvoid.webrtc.media.audio.codec;

import dev.onvoid.webrtc.internal.NativeLoader;

import java.util.Arrays;
import java.util.List;

/**
 * WebRTC's built-in audio encoders: Opus, G722, PCMU and PCMA. Without codec
 * names it offers all of them, which is what a {@link
 * dev.onvoid.webrtc.PeerConnectionFactory} uses by default. With names it
 * offers only those codecs, in the given order, for example only Opus:
 * <pre>{@code
 * PeerConnectionFactory factory = PeerConnectionFactory.builder()
 *     .setAudioEncoderFactory(new BuiltinAudioEncoderFactory("opus"))
 *     .build();
 * }</pre>
 * <p>
 * WebRTC adds the auxiliary codecs by itself, depending on the selected
 * ones: "telephone-event" for DTMF at their clock rates of 8000 and 48000 Hz,
 * "CN" for comfort noise next to 8000 Hz codecs, and "red" next to Opus. The
 * {@link #getSupportedCodecs() supported codecs} do not list them.
 *
 * @author Alex Andres
 */
public final class BuiltinAudioEncoderFactory extends AudioEncoderFactory {

	static {
		try {
			NativeLoader.loadLibrary("webrtc-java");
		}
		catch (Exception e) {
			throw new RuntimeException("Load library 'webrtc-java' failed", e);
		}
	}


	/* Read natively when the PeerConnectionFactory is built. */
	private final String[] codecNames;

	private final List<AudioCodecInfo> supportedCodecs;


	/**
	 * Creates a factory with the given built-in codecs, or all of them when
	 * no names are given.
	 *
	 * @param codecNames The names of the codecs to offer, in order of
	 *                   preference, compared ignoring case.
	 *
	 * @throws IllegalArgumentException If a name is not a built-in codec.
	 */
	public BuiltinAudioEncoderFactory(String... codecNames) {
		List<AudioCodecInfo> builtin = Arrays.asList(getBuiltinCodecs());

		this.codecNames = AudioCodecSelection.canonicalNames(builtin, codecNames);
		this.supportedCodecs = AudioCodecSelection.select(builtin, this.codecNames);
	}

	@Override
	public List<AudioCodecInfo> getSupportedCodecs() {
		return supportedCodecs;
	}

	private static native AudioCodecInfo[] getBuiltinCodecs();

}
