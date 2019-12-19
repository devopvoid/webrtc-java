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
 * Provides information on RTCP settings.
 *
 * @author Alex Andres
 */
public class RTCRtcpParameters {

	/**
	 * The Canonical Name (CNAME) used by RTCP (e.g. in SDES messages).
	 */
	public final String cName;

	/**
	 * Whether reduced size RTCP is configured (if true) or compound RTCP.
	 */
	public final boolean reducedSize;


	/**
	 * Creates an instance of RTCRtcpParameters with the specified canonical
	 * name and the reduced size flag.
	 *
	 * @param cName       The Canonical Name used by RTCP.
	 * @param reducedSize True if reduced size RTCP is configured, false
	 *                    otherwise.
	 */
	public RTCRtcpParameters(String cName, boolean reducedSize) {
		this.cName = cName;
		this.reducedSize = reducedSize;
	}

	@Override
	public String toString() {
		return String.format("%s [cName=%s, reducedSize=%s]",
				RTCRtcpParameters.class.getSimpleName(), cName, reducedSize);
	}
}
