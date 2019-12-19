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

import java.util.List;

/**
 * Contains the capabilities of the system for receiving media. The
 * RTCRtpCapabilities class is meant to provide a way to discover the types of
 * capabilities of the application including which codecs may be supported.
 *
 * @author Alex Andres
 */
public class RTCRtpCapabilities {

	/**
	 * Supported media codecs as well as entries for RTX, RED and FEC
	 * mechanisms.
	 */
	private final List<RTCRtpCodecCapability> codecs;

	/**
	 * Supported RTP header extensions.
	 */
	private final List<RTCRtpHeaderExtensionCapability> headerExtensions;


	/**
	 * Creates an instance of RTCRtpCapabilities with the supported codec and
	 * RTP header extension capabilities.
	 *
	 * @param codecs           The supported media codecs.
	 * @param headerExtensions The supported RTP header extensions.
	 */
	public RTCRtpCapabilities(List<RTCRtpCodecCapability> codecs,
			List<RTCRtpHeaderExtensionCapability> headerExtensions) {
		this.codecs = codecs;
		this.headerExtensions = headerExtensions;
	}

	/**
	 * Returns the supported media codecs as well as entries for RTX, RED and
	 * FEC mechanisms.
	 *
	 * @return The supported media codecs.
	 */
	public List<RTCRtpCodecCapability> getCodecs() {
		return codecs;
	}

	/**
	 * Returns the supported RTP header extensions.
	 *
	 * @return The supported RTP header extensions.
	 */
	public List<RTCRtpHeaderExtensionCapability> getHeaderExtensions() {
		return headerExtensions;
	}

	@Override
	public String toString() {
		return String.format("%s [codecs=%s, headerExtensions=%s]",
				RTCRtpCapabilities.class.getSimpleName(), codecs,
				headerExtensions);
	}
}
