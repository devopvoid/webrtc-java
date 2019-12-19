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
 * The RTCRtpReceiver allows an application to inspect the receipt of a {@link
 * MediaStreamTrack}.
 *
 * @author Alex Andres
 */
public class RTCRtpReceiver extends NativeObject {

	/**
	 * Constructor to be used by the native api.
	 */
	private RTCRtpReceiver() {

	}

	/**
	 * The track that is associated with this RTCRtpReceiver object. Note that
	 * track.stop() is final, although clones are not affected. Since
	 * receiver.track.stop() does not implicitly stop receiver, Receiver Reports
	 * continue to be sent.
	 *
	 * @return The media track associated with this receiver.
	 */
	public native MediaStreamTrack getTrack();

	/**
	 * The transport over which media for the receiver's track is received in
	 * the form of RTP packets. When bundling is used, multiple RTCRtpReceivers
	 * will share one transport and will all receive RTP and RTCP over the same
	 * transport.
	 *
	 * @return The transport over which media for the receiver's track is
	 * received.
	 */
	public native RTCDtlsTransport getTransport();

	/**
	 * Returns the RTCRtpReceiver's current parameters containing information
	 * about how the track is decoded.
	 *
	 * @return The current parameters.
	 */
	public native RTCRtpParameters getParameters();

	/**
	 * Returns an RTCRtpContributingSource for each unique CSRC identifier
	 * received by this RTCRtpReceiver in the last 10 seconds, in descending
	 * timestamp order.
	 *
	 * @return A list of contributing sources in descending timestamp order.
	 */
	public native List<RTCRtpContributingSource> getContributingSources();

	/**
	 * Returns an RTCRtpSynchronizationSource for each unique SSRC identifier
	 * received by this RTCRtpReceiver in the last 10 seconds, in descending
	 * timestamp order.
	 *
	 * @return A list of synchronization sources in descending timestamp order.
	 */
	public native List<RTCRtpSynchronizationSource> getSynchronizationSources();

}
