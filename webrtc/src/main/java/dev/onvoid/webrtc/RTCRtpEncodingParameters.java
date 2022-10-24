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
 * Describes encoding options of an {@link RTCRtpSender}.
 *
 * @author Alex Andres
 */
public class RTCRtpEncodingParameters {

	/**
	 * If unset, a value is chosen by the implementation.
	 * <br>
	 * Note that the chosen value is NOT returned by GetParameters, because it
	 * may change due to an SSRC conflict, in which case the conflict is handled
	 * internally without any event. Another way of looking at this is that an
	 * unset SSRC acts as a "wildcard" SSRC.
	 */
	public Long ssrc;

	/**
	 * Indicates that this encoding is actively being sent. Setting it to false
	 * causes this encoding to no longer be sent. Setting it to true causes this
	 * encoding to be sent.
	 */
	public Boolean active;

	/**
	 * When present, indicates the maximum bitrate that can be used to send this
	 * encoding. If unset, there is no maximum bitrate.
	 */
	public Integer maxBitrate;

	/**
	 * When present, indicates the minimum bitrate that can be used to send this
	 * encoding. If unset, there is no minimum bitrate.
	 */
	public Integer minBitrate;

	/**
	 * When present, indicates the maximum frame rate that can be used to send
	 * this encoding, in frames per second.
	 */
	public Double maxFramerate;

	/**
	 * Only present if the sender's kind is "video". The video's resolution will
	 * be scaled down in each dimension by the given value before sending. For
	 * example, if the value is 2.0, the video will be scaled down by a factor
	 * of 2 in each dimension, resulting in sending a video of one quarter the
	 * size. If the value is 1.0, the video will not be affected. The value must
	 * be greater than or equal to 1.0. By default, the sender will not apply
	 * any scaling
	 */
	public Double scaleResolutionDownBy;


	/**
	 * Creates an instance of RTCRtpEncodingParameters.
	 */
	public RTCRtpEncodingParameters() {
		active = true;
	}

	@Override
	public String toString() {
		return "RTCRtpEncodingParameters{" + "ssrc=" + ssrc + ", active="
				+ active + ", maxBitrate=" + maxBitrate + ", minBitrate="
				+ minBitrate + ", maxFramerate=" + maxFramerate
				+ ", scaleResolutionDownBy=" + scaleResolutionDownBy + '}';
	}
}
