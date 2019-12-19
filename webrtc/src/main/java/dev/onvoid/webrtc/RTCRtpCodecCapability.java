/*
 * Copyright 2019 Alex Andres
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

package dev.onvoid.webrtc;

import dev.onvoid.webrtc.media.MediaType;

import java.util.Map;

/**
 * Represents the static capabilities of an endpoint's implementation of a
 * codec.
 *
 * @author Alex Andres
 */
public class RTCRtpCodecCapability {

	/**
	 * The media type of the codec. Equivalent to the MIME top-level type.
	 */
	private final MediaType mediaType;

	/**
	 * Used to identify the codec. Equivalent to the MIME subtype.
	 */
	private final String name;

	/**
	 * The codec clock rate expressed in Hertz.
	 */
	private final int clockRate;

	/**
	 * When present, indicates the number of audio channels (mono=1, stereo=2).
	 * <p>
	 * Unused for video codecs.
	 */
	private final int channels;

	/**
	 * The "format specific parameters" from the "a=fmtp" line in the SDP
	 * corresponding to the codec. These parameters are named using all
	 * lowercase strings. Boolean values are represented by the string "1".
	 */
	private final Map<String, String> sdpFmtp;


	/**
	 * Creates an instance of RTCRtpCodecCapability with the specified
	 * capability parameters.
	 *
	 * @param mediaType The media type of the codec. Equivalent to the MIME
	 *                  top-level type.
	 * @param name      The codec name. Equivalent to the MIME subtype.
	 * @param clockRate The codec clock rate expressed in Hertz.
	 * @param channels  The number of audio channels. Unused for video codecs.
	 * @param sdpFmtp   The "a=fmtp" parameters in the SDP.
	 */
	public RTCRtpCodecCapability(MediaType mediaType, String name,
			int clockRate, int channels, Map<String, String> sdpFmtp) {
		this.mediaType = mediaType;
		this.name = name;
		this.clockRate = clockRate;
		this.channels = channels;
		this.sdpFmtp = sdpFmtp;
	}

	/**
	 * Returns media type of the codec equivalent to the MIME top-level type.
	 *
	 * @return The media type of the codec.
	 */
	public MediaType getMediaType() {
		return mediaType;
	}

	/**
	 * Returns the name of the codec equivalent to the MIME subtype.
	 *
	 * @return The name of the codec.
	 */
	public String getName() {
		return name;
	}

	/**
	 * Returns the codec clock rate expressed in Hertz.
	 *
	 * @return The codec clock rate expressed in Hertz.
	 */
	public int getClockRate() {
		return clockRate;
	}

	/**
	 * Returns the number of audio channels (mono=1, stereo=2).
	 * <p>
	 * Unused for video codecs.
	 *
	 * @return The number of audio channels.
	 */
	public int getChannels() {
		return channels;
	}

	/**
	 * Returns the format specific parameters in the SDP corresponding to the
	 * codec. The map contains the "a=fmtp" line in the SDP for convenient
	 * access of the values.
	 *
	 * @return The format specific parameters in the SDP.
	 */
	public Map<String, String> getSDPFmtp() {
		return sdpFmtp;
	}

	/**
	 * Returns the MIME type composed of the {@code mediaType} and the {@code
	 * name}.
	 *
	 * @return The codec MIME type.
	 */
	public String getMimeType() {
		return mediaType.toString().toLowerCase() + "/" + name;
	}

	@Override
	public String toString() {
		return String.format("%s [mediaType=%s, name=%s, clockRate=%s, channels=%s, sdpFmtp=%s]",
				RTCRtpCodecCapability.class.getSimpleName(), mediaType, name,
				clockRate, channels, sdpFmtp);
	}
}
