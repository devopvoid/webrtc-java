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
 * Callback interface used to obtain {@link RTCSessionDescription}s when calling
 * either {@link RTCPeerConnection#createOffer createOffer} or {@link
 * RTCPeerConnection#createAnswer createAnswer}.
 *
 * @author Alex Andres
 */
public interface CreateSessionDescriptionObserver {

	/**
	 * Receives the generated SDP answer/offer. The SDP contains descriptions of
	 * the local MediaStreamTracks attached to the RTCPeerConnection, the
	 * codec/RTP/RTCP options negotiated for the current session, and any
	 * candidates that have been gathered by the ICE Agent.
	 *
	 * @param description The generated answer/offer session description.
	 */
	void onSuccess(RTCSessionDescription description);

	/**
	 * An error has occurred, causing the RTCPeerConnection to abort the SDP
	 * generation procedure.
	 *
	 * @param error The error message.
	 */
	void onFailure(String error);

}
