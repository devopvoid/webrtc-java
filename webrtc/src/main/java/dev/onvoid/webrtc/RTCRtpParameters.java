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
 * Describes RTP stack settings used by both {@link RTCRtpSender}s and {@link
 * RTCRtpReceiver}s.
 *
 * @author Alex Andres
 */
public class RTCRtpParameters {

	/**
	 * A list containing parameters for RTP header extensions.
	 */
	public List<RTCRtpHeaderExtensionParameters> headerExtensions;

	/**
	 * Parameters used for RTCP.
	 */
	public RTCRtcpParameters rtcp;

	/**
	 * A list containing the media codecs that an RTCRtpSender will choose from,
	 * as well as entries for RTX, RED and FEC mechanisms. Corresponding to each
	 * media codec where retransmission via RTX is enabled, there will be an
	 * entry in codecs with a mimeType attribute indicating retransmission via
	 * "audio/rtx" or "video/rtx", and an sdpFmtpLine attribute (providing the
	 * "apt" and "rtx-time" parameters).
	 */
	public List<RTCRtpCodecParameters> codecs;


	@Override
	public String toString() {
		return String.format("%s [headerExtensions=%s, rtcp=%s, codecs=%s]",
				RTCRtpParameters.class.getSimpleName(), headerExtensions, rtcp,
				codecs);
	}
}
