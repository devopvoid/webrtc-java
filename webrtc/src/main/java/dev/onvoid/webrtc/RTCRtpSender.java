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

import dev.onvoid.webrtc.internal.DisposableNativeObject;
import dev.onvoid.webrtc.media.MediaStreamTrack;

/**
 * The RTCRtpSender allows an application to control how a given {@link
 * MediaStreamTrack} is encoded and transmitted to a remote peer. When {@link
 * #setParameters} is called on an RTCRtpSender, the encoding is changed
 * appropriately.
 *
 * @author Alex Andres
 */
public class RTCRtpSender extends DisposableNativeObject {

	/*
	 * The native observer set through this object, owned by it and freed when
	 * replaced, removed or disposed.
	 */
	@SuppressWarnings("unused")
	private long observerHandle;


	/**
	 * Constructor to be used by the native api.
	 */
	private RTCRtpSender() {

	}

	/**
	 * Returns the ID of this sender, which is unique within its peer
	 * connection. It is the same for all RTCRtpSender objects of the same
	 * sender.
	 *
	 * @return The ID of this sender.
	 */
	public native String getId();

	/**
	 * Sets the observer that is told when this sender sends its first RTP
	 * packet, replacing any observer set before. {@code null} removes the
	 * observer.
	 * <p>
	 * A sender has one observer. It belongs to the RTCRtpSender object it was
	 * set through: disposing that object removes it, also when it was set
	 * through another object of the same sender in the meantime, so set it
	 * through one object.
	 *
	 * @param observer The observer, or {@code null}.
	 */
	public native void setObserver(RTCRtpSenderObserver observer);

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

	/**
	 * Returns the IDs of the media streams associated with this sender's
	 * track, as given to {@link RTCPeerConnection#addTrack addTrack}, the
	 * {@link RTCRtpTransceiverInit} or {@link #setStreams}.
	 *
	 * @return The IDs of the media streams.
	 */
	public native List<String> getStreams();

	/**
	 * Returns the RTCDtmfSender associated with this RTCRtpSender. The RTCDtmfSender
	 * enables the transmission of DTMF (Dual-Tone Multi-Frequency) tones over the
	 * RTCPeerConnection.
	 *
	 * @return The DTMF sender object associated with this RTP sender, or null if DTMF
	 *         is not supported for the media type of the associated track.
	 */
	public native RTCDtmfSender getDtmfSender();

	/**
	 * Sets the transform that every encoded frame of this sender passes
	 * through between the encoder and the packetizer, replacing the one set
	 * before. A transform of {@code null} removes it, after which frames pass
	 * unchanged.
	 * <p>
	 * The transform belongs to the native sender, so it is shared by every
	 * RTCRtpSender instance standing for it, and it stays in place when this
	 * instance is disposed. Setting it before the connection is negotiated
	 * avoids the key frame the encoder otherwise sends when the first
	 * transform is set on a running video sender.
	 *
	 * @param transformer The transform, or {@code null} to remove it.
	 *
	 * @see RTCEncodedFrameTransformer
	 */
	public native void setTransform(RTCEncodedFrameTransformer transformer);

	/**
	 * Asks the encoder to make the next video frame a key frame, e.g. so that
	 * a newly joined receiver or a recording can start decoding at once. Does
	 * nothing for an audio sender.
	 *
	 * @throws RuntimeException If the sender has no encoder yet, before the
	 *                          connection is negotiated.
	 */
	public native void generateKeyFrame();

	/**
	 * Releases the native reference held by this RTCRtpSender instance.
	 * <p>
	 * An RTCRtpSender is not exclusively owned by this instance: the
	 * underlying sender may be kept alive by its {@link RTCRtpTransceiver}
	 * or by other RTCRtpSender instances obtained via separate calls to
	 * {@link RTCPeerConnection#getSenders()} or {@link
	 * RTCRtpTransceiver#getSender()}. Disposing this instance only drops the
	 * reference it holds and does not affect the sender itself or any other
	 * instance referring to it.
	 */
	@Override
	public native void dispose();

	/**
	 * Two RTCRtpSender instances are equal if they are bound to the same
	 * native sender, e.g. when obtained from two separate calls to {@link
	 * RTCPeerConnection#getSenders()}. A disposed instance is never equal to
	 * anything but itself.
	 */
	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (!(obj instanceof RTCRtpSender)) {
			return false;
		}

		long handle = getNativeHandle();

		return handle != 0 && handle == ((RTCRtpSender) obj).getNativeHandle();
	}

	@Override
	public int hashCode() {
		long handle = getNativeHandle();

		return handle == 0 ? System.identityHashCode(this) : Long.hashCode(handle);
	}

}
