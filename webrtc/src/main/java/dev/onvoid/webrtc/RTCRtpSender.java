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

import dev.onvoid.webrtc.internal.NativeObject;
import dev.onvoid.webrtc.media.MediaStreamTrack;

/**
 * The RTCRtpSender allows an application to control how a given {@link
 * MediaStreamTrack} is encoded and transmitted to a remote peer. When {@link
 * #setParameters} is called on an RTCRtpSender, the encoding is changed
 * appropriately.
 *
 * @author Alex Andres
 */
public class RTCRtpSender extends NativeObject {

	/**
	 * Constructor to be used by the native api.
	 */
	private RTCRtpSender() {

	}

	/**
	 * Returns the track that is associated with this RTCRtpSender. If track is
	 * ended, or if the track's output is disabled, i.e. the track is disabled
	 * and/or muted, the RTCRtpSender MUST send silence (audio), black frames
	 * (video) or a zero-information-content equivalent. In the case of video,
	 * the RTCRtpSender SHOULD send one black frame per second. If track is
	 * {@code null} then the RTCRtpSender does not send.
	 *
	 * @return The media track associated with this sender.
	 */
	public native MediaStreamTrack getTrack();

	/**
	 * The transport over which media from the MediaStreamTrack is sent in the
	 * form of RTP packets. When bundling is used, multiple RTCRtpSenders will
	 * share one transport and will all send RTP and RTCP over the same
	 * transport.
	 *
	 * @return The transport over which media from the MediaStreamTrack is sent.
	 */
	public native RTCDtlsTransport getTransport();

	/**
	 * Attempts to replace the RTCRtpSender's current track with another track
	 * provided (or with a null track), without renegotiation. To avoid track
	 * identifiers changing on the remote receiving end when a track is
	 * replaced, the sender MUST retain the original track identifier and stream
	 * associations and use these in subsequent negotiations.
	 *
	 * @param withTrack The new media track.
	 */
	public native void replaceTrack(MediaStreamTrack withTrack);

	/**
	 * Updates how track is encoded and transmitted to a remote peer. Does not
	 * cause SDP renegotiation and can only be used to change what the media
	 * stack is sending or receiving within the envelope negotiated by
	 * Offer/Answer. The attributes in the RTCRtpSendParameters are designed to
	 * not enable this, so attributes like cname that cannot be changed are
	 * read-only. Other things, like bitrate, are controlled using limits such
	 * as maxBitrate, where the user agent needs to ensure it does not exceed
	 * the maximum bitrate specified by maxBitrate, while at the same time
	 * making sure it satisfies constraints on bitrate specified in other places
	 * such as the SDP.
	 *
	 * @param parameters The new RTP parameters.
	 */
	public native void setParameters(RTCRtpSendParameters parameters);

	/**
	 * Returns the RTCRtpSender's current parameters for how track is encoded
	 * and transmitted to a remote RTCRtpReceiver.
	 *
	 * @return The current RTP parameters.
	 */
	public native RTCRtpSendParameters getParameters();

	/**
	 * Sets the IDs of the media streams associated with this sender's track.
	 *
	 * @param streamIds The IDs of the media streams.
	 */
	public native void setStreams(List<String> streamIds);

}
