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
 * Describes the configuration parameters for a single media codec.
 *
 * @author Alex Andres
 */
public class RTCRtpCodecParameters {

	/**
	 * The RTP payload type used to identify this codec.
	 */
	public final int payloadType;

	/**
	 * The media type of the codec. Equivalent to the MIME top-level type.
	 */
	public final MediaType mediaType;

	/**
	 * Used to identify the codec. Equivalent to the MIME subtype.
	 */
	public final String codecName;

	/**
	 * The codec clock rate expressed in Hertz.
	 */
	public final Integer clockRate;

	/**
	 * When present, indicates the number of audio channels (mono=1, stereo=2).
	 */
	public final Integer channels;

	/**
	 * The "format specific parameters" field from the "a=fmtp" line in the SDP
	 * corresponding to the codec, if one exists. For an RTCRtpSender, these
	 * parameters come from the remote description, and for an RTCRtpReceiver,
	 * they come from the local description.
	 */
	public final Map<String, String> parameters;


	/**
	 * Creates an instance of RTCRtpCodecParameters with the specified media
	 * codec parameters.
	 *
	 * @param payloadType The RTP payload type used to identify the codec.
	 * @param mediaType   The media type of the codec.
	 * @param codecName   The codec name. Equivalent to the MIME subtype.
	 * @param clockRate   The codec clock rate expressed in Hertz.
	 * @param channels    The number of audio channels. Unused for video codecs.
	 * @param parameters  The "a=fmtp" parameters in the SDP.
	 */
	public RTCRtpCodecParameters(int payloadType, MediaType mediaType,
			String codecName, Integer clockRate, Integer channels,
			Map<String, String> parameters) {
		this.payloadType = payloadType;
		this.mediaType = mediaType;
		this.codecName = codecName;
		this.clockRate = clockRate;
		this.channels = channels;
		this.parameters = parameters;
	}

	@Override
	public String toString() {
		return String.format("%s [payloadType=%s, mediaType=%s, codecName=%s, clockRate=%s, channels=%s, parameters=%s]",
				RTCRtpCodecParameters.class.getSimpleName(), payloadType,
				mediaType, codecName, clockRate, channels, parameters);
	}
}
