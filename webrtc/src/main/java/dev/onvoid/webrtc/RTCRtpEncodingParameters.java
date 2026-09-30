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
	 * The RTP stream ID of the encoding, which tells simulcast encodings apart,
	 * e.g. "f", "h" and "q" for full, half and quarter resolution. Set only
	 * when the encodings are given to {@link RTCRtpTransceiverInit}; it cannot
	 * be changed afterwards. Unset, or empty, without simulcast.
	 */
	public String rid;

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
	 * Only for video. The largest resolution to send this encoding in: the
	 * video is scaled down to fit. Takes precedence over {@link
	 * #scaleResolutionDownBy} if both are set. If unset, the resolution is not
	 * restricted this way.
	 */
	public RTCResolutionRestriction scaleResolutionDownTo;

	/**
	 * Only for video. The scalability mode of the encoding, as named in the
	 * WebRTC SVC specification, e.g. "L1T3" for three temporal layers or
	 * "L3T3_KEY" for three spatial layers with three temporal layers each. The
	 * codec has to support the mode, which {@link RTCRtpCodecCapability}
	 * lists; setting one it does not support fails. If unset, the encoder
	 * chooses its layers, or follows {@link #numTemporalLayers}.
	 */
	public String scalabilityMode;

	/**
	 * Only for video. The number of temporal layers to encode, if the codec
	 * supports temporal layers. An older way to ask for them than {@link
	 * #scalabilityMode}; set one of the two. If unset, the encoder chooses.
	 */
	public Integer numTemporalLayers;

	/**
	 * The share of the available bitrate the sender gets relative to the other
	 * senders of the peer connection. It applies to the whole sender, so only
	 * the first encoding may set it; setting it on another fails. If unset,
	 * the default of 1.0.
	 */
	public Double bitratePriority;

	/**
	 * The priority the packets of the sender are marked with on the network
	 * (DSCP), where the network honors it. It applies to the whole sender, so
	 * only the first encoding may set it; setting it on another fails. If
	 * unset, {@link RTCPriorityType#LOW}.
	 */
	public RTCPriorityType networkPriority;

	/**
	 * Only for audio. Whether the encoder may send longer audio packets when
	 * the bitrate is low, which saves the overhead of many small packets. If
	 * unset, it does not.
	 */
	public Boolean adaptivePtime;

	/**
	 * The codec to send this encoding with, one of those negotiated, which
	 * lets simulcast encodings use different codecs. If unset, the encoding
	 * uses the codec the negotiation settled on.
	 */
	public RTCRtpCodecCapability codec;


	/**
	 * Creates an instance of RTCRtpEncodingParameters.
	 */
	public RTCRtpEncodingParameters() {
		active = true;
	}

	@Override
	public String toString() {
		return "RTCRtpEncodingParameters{" + "rid=" + rid + ", ssrc=" + ssrc
				+ ", active=" + active + ", maxBitrate=" + maxBitrate
				+ ", minBitrate=" + minBitrate + ", maxFramerate=" + maxFramerate
				+ ", scaleResolutionDownBy=" + scaleResolutionDownBy
				+ ", scaleResolutionDownTo=" + scaleResolutionDownTo
				+ ", scalabilityMode=" + scalabilityMode
				+ ", numTemporalLayers=" + numTemporalLayers
				+ ", bitratePriority=" + bitratePriority
				+ ", networkPriority=" + networkPriority
				+ ", adaptivePtime=" + adaptivePtime
				+ ", codec=" + codec + '}';
	}
}
