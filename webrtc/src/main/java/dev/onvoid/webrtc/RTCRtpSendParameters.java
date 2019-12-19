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
 * Specifies RTP and RTCP parameters for an {@link RTCRtpSender}.
 *
 * @author Alex Andres
 */
public class RTCRtpSendParameters extends RTCRtpParameters {

	/**
	 * An unique identifier for the last set of parameters applied. Ensures that
	 * setParameters can only be called based on a previous getParameters, and
	 * that there are no intervening changes.
	 */
	public String transactionId;

	/**
	 * A list containing parameters for RTP encodings of media.
	 */
	public List<RTCRtpEncodingParameters> encodings;


	@Override
	public String toString() {
		return String.format("%s [transactionId=%s, encodings=%s, headerExtensions=%s, rtcp=%s, codecs=%s]",
				RTCRtpSendParameters.class.getSimpleName(), transactionId,
				encodings, headerExtensions, rtcp, codecs);
	}
}
