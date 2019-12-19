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

/**
 * Describes supported RTP header extensions.
 *
 * @author Alex Andres
 */
public class RTCRtpHeaderExtensionCapability {

	/**
	 * The URI of the RTP header extension.
	 */
	private final String uri;


	/**
	 * Creates an instance of RTCRtpHeaderExtensionCapability with the specified
	 * RTP header extension URI.
	 *
	 * @param uri The URI of the RTP header extension.
	 */
	public RTCRtpHeaderExtensionCapability(String uri) {
		this.uri = uri;
	}

	/**
	 * Returns the URI of the supported RTP header extension by the system.
	 *
	 * @return The URI of the RTP header extension.
	 */
	public String getUri() {
		return uri;
	}

	@Override
	public String toString() {
		return String.format("%s [uri=%s]",
				RTCRtpHeaderExtensionCapability.class.getSimpleName(), uri);
	}
}
