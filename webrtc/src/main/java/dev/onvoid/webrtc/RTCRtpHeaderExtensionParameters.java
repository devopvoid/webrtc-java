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
 * Enables an application to determine whether a header extension is configured
 * for use within an RTCRtpSender or RTCRtpReceiver.
 *
 * @author Alex Andres
 */
public class RTCRtpHeaderExtensionParameters {

	/**
	 * The URI of the RTP header extension.
	 */
	public final String uri;

	/**
	 * The value put in the RTP packet to identify the header extension.
	 */
	public final int id;

	/**
	 * Whether the header extension is encrypted or not.
	 */
	public final boolean encrypted;


	/**
	 * Creates an instance of RTCRtpHeaderExtensionParameters with the specified
	 * header extension parameters.
	 *
	 * @param uri       The URI of the RTP header extension.
	 * @param id        The value put in the RTP packet to identify the header
	 *                  extension.
	 * @param encrypted Whether the header extension is encrypted or not.
	 */
	public RTCRtpHeaderExtensionParameters(String uri, int id, boolean encrypted) {
		this.uri = uri;
		this.id = id;
		this.encrypted = encrypted;
	}

	@Override
	public String toString() {
		return String.format("%s [uri=%s, id=%s, encrypted=%s]",
				RTCRtpHeaderExtensionParameters.class.getSimpleName(), uri, id,
				encrypted);
	}
}
