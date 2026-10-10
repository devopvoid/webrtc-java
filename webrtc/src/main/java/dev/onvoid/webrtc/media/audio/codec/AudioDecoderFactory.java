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

import java.util.List;

/**
 * Creates the audio decoders of a {@link dev.onvoid.webrtc.PeerConnectionFactory},
 * and so decides which audio codecs it can receive. Set one with {@link
 * dev.onvoid.webrtc.PeerConnectionFactory.Builder#setAudioDecoderFactory}.
 * <p>
 * The decoders are WebRTC's own; {@link BuiltinAudioDecoderFactory} is the
 * implementation.
 *
 * @author Alex Andres
 */
public abstract class AudioDecoderFactory {

	AudioDecoderFactory() {
	}

	/**
	 * Returns the codecs this factory can decode, in order of preference.
	 *
	 * @return The supported codecs, which cannot be modified.
	 */
	public abstract List<AudioCodecInfo> getSupportedCodecs();

}
